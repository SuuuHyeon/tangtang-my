package com.kb.tangtang.account.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.kb.tangtang.account.mapper.ConnectedAccountMapper;

/**
 * 이슈 #199 — 계좌·카드 연동이 있는 사용자를 주기적으로 자동 동기화한다.
 * LlmCategorizationScheduler/NotificationDlqRetryScheduler 와 같은 패턴: 한 사용자가 실패해도
 * 배치 전체가 멈추지 않는다.
 *
 * ⚠ 이 클래스는 "누구를, 몇 명까지" 만 결정한다. 실제 동기화 로직은 전혀 갖지 않고
 *   FinancialSyncServiceImpl.sync() 를 그대로 재사용한다 — 수동 트리거(POST /sync)와 완전히
 *   같은 파이프라인이다. 별도 잠금·선점 테이블이 없는 이유: @Scheduled(fixedDelay) 는 이전
 *   실행이 끝나야 다음이 시작돼 이 메서드 자체는 절대 중첩 실행되지 않고, sync() 내부의 저장은
 *   자연키 upsert 라 수동 트리거와 배치가 같은 사용자를 우연히 겹쳐 돌려도 안전하다
 *   (FinancialSyncServiceImpl 클래스 Javadoc 「동시 insert 경합」 참고).
 *
 * ⚠ 이 무잠금 설계는 **단일 JVM 배포를 전제**한다(최종 리뷰 후속, PR 코멘트 P1).
 *   {@code @Scheduled(fixedDelay)} 는 JVM 하나 안에서만 중첩 실행을 막는다 — 인스턴스를 2대
 *   이상으로 스케일아웃하면 두 인스턴스의 배치가 같은 사용자를 동시에 돌릴 수 있고, 그때부터는
 *   전적으로 자연키 upsert 의 DuplicateKeyException 재시도 하나에만 기대게 된다. 지금은
 *   `docker-compose.yml`/EC2 배포가 API 컨테이너 1개뿐이라 안전하지만, 다중 인스턴스로
 *   전환하면 사용자별 DB 선점 상태나 분산 락을 다시 검토해야 한다.
 */
@Component
public class FinancialSyncBatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(FinancialSyncBatchScheduler.class);

    private final ConnectedAccountMapper connectedAccountMapper;
    private final FinancialSyncService financialSyncService;
    private final boolean enabled;
    private final int maxUsersPerTick;
    /** 재동기화 쿨다운(분). 마지막 동기화 시도가 이보다 최근인 사용자는 이번 틱에서 뺀다(이슈 #199 최종 리뷰). */
    private final int minIntervalMinutes;

    private final Timer batchTimer;
    private final Counter syncSuccess;
    private final Counter syncFailure;

    @Autowired
    public FinancialSyncBatchScheduler(ConnectedAccountMapper connectedAccountMapper,
                                       FinancialSyncService financialSyncService,
                                       @Value("${financial.sync.batch.enabled}") boolean enabled,
                                       @Value("${financial.sync.batch.max-users-per-tick}") int maxUsersPerTick,
                                       @Value("${financial.sync.batch.min-interval-minutes}") int minIntervalMinutes,
                                       MeterRegistry meterRegistry) {
        this.connectedAccountMapper = connectedAccountMapper;
        this.financialSyncService = financialSyncService;
        this.enabled = enabled;
        this.maxUsersPerTick = maxUsersPerTick;
        this.minIntervalMinutes = minIntervalMinutes;
        // 생성자 마지막 파라미터에 MeterRegistry meterRegistry 를 추가하고
        this.batchTimer = Timer.builder("batch.execution")
                .tag("batch", "financial-sync")
                .register(meterRegistry);
        this.syncSuccess = Counter.builder("batch.items")
                .tag("batch", "financial-sync").tag("result", "success")
                .register(meterRegistry);
        this.syncFailure = Counter.builder("batch.items")
                .tag("batch", "financial-sync").tag("result", "failure")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${financial.sync.batch.fixed-delay-ms}")
    public void runBatch() {
        if (!enabled) {
            return;
        }
        batchTimer.record(() -> {
            for (Long userId : connectedAccountMapper.findUserIdsDueForSync(minIntervalMinutes, maxUsersPerTick)) {
                try {
                    financialSyncService.sync(userId);
                    syncSuccess.increment();
                } catch (Exception e) {
                    syncFailure.increment();
                    log.error("배치 동기화 실패 userId={}", userId, e);
                }
            }
        });
    }
}
