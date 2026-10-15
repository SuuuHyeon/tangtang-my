package com.kb.tangtang.challenge.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 그룹 챌린지 평가·기소 배치의 실행 주기만 담당한다 (이슈 #168).
 *
 * <p>판정 로직을 두지 않는다 — DEV 수동 트리거와 단위 테스트가 배치 서비스를 직접 부르기 때문에,
 * 여기에 로직이 있으면 그 경로들이 다른 코드를 타게 된다.
 * ({@link ChallengeGroupStatusScheduler} 와 같은 구조)
 *
 * <p><b>cron 이 아니라 {@code fixedDelay} 다.</b> 하루 한 번이 아니라 5분마다 도는 배치이고,
 * {@code fixedRate} 와 달리 이전 실행이 끝난 뒤부터 시간을 재서 느린 틱이 다음 틱과 겹치지 않는다.
 * 겹치면 같은 그룹을 두 스레드가 동시에 평가해 중복 기소 경합이 늘어난다.
 *
 * <p>거래 동기화 배치와 주기를 맞춰 두는 것이 좋지만 <b>맞아야만 동작하는 것은 아니다.</b>
 * 동기화가 늦어도 다음 틱이 어제·오늘을 다시 집계하므로 누락되지 않는다.
 */
@Component
public class GroupChallengeEvaluationScheduler {

    private final GroupChallengeEvaluationBatchService batchService;
    private final ZoneId zoneId;
    private final Timer evaluationTimer;

    public GroupChallengeEvaluationScheduler(
            GroupChallengeEvaluationBatchService batchService,
            @Value("${challenge.group.evaluation.zone}") String zoneId,
            MeterRegistry meterRegistry) {
        this.evaluationTimer = Timer.builder("batch.execution")
                .description("배치 1회 실행 시간")
                .tag("batch", "group-challenge-evaluation")
                .register(meterRegistry);
        this.batchService = batchService;
        this.zoneId = ZoneId.of(zoneId);
    }

    @Scheduled(fixedDelayString = "${challenge.group.evaluation.fixed-delay-ms}")
    public void evaluateActiveGroups() {
        evaluationTimer.record(() -> batchService.evaluateActiveGroups(LocalDate.now(zoneId)));

    }
}
