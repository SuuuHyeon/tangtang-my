package com.kb.tangtang.account.service;

import com.kb.tangtang.account.mapper.ConnectedAccountMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 이슈 #199 — 배치 스케줄러 단위 테스트. LlmCategorizationSchedulerTest 와 같은 방식:
 * 매퍼·서비스는 전부 목이라 DB 없이 돈다.
 * 지표는 메모리에만 기록하는 SimpleMeterRegistry 로 받는다.
 */
class FinancialSyncBatchSchedulerTest {

    private ConnectedAccountMapper connectedAccountMapper;
    private FinancialSyncService financialSyncService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        connectedAccountMapper = mock(ConnectedAccountMapper.class);
        financialSyncService = mock(FinancialSyncService.class);
        meterRegistry = new SimpleMeterRegistry();   // 테스트마다 새로 만들어 값이 섞이지 않게
    }

    @Test
    @DisplayName("대상 사용자 각각에 대해 sync() 를 호출한다")
    void callsSyncForEachDueUser() {
        when(connectedAccountMapper.findUserIdsDueForSync(25, 20)).thenReturn(List.of(1L, 2L, 3L));
        FinancialSyncBatchScheduler scheduler = new FinancialSyncBatchScheduler(
                connectedAccountMapper, financialSyncService, true, 20, 25, meterRegistry);

        scheduler.runBatch();

        verify(financialSyncService).sync(1L);
        verify(financialSyncService).sync(2L);
        verify(financialSyncService).sync(3L);
    }

    @Test
    @DisplayName("한 사용자가 실패해도 나머지 사용자는 계속 처리한다")
    void oneFailureDoesNotStopTheRest() {
        when(connectedAccountMapper.findUserIdsDueForSync(25, 20)).thenReturn(List.of(1L, 2L, 3L));
        when(financialSyncService.sync(2L)).thenThrow(new RuntimeException("동기화 실패"));
        FinancialSyncBatchScheduler scheduler = new FinancialSyncBatchScheduler(
                connectedAccountMapper, financialSyncService, true, 20, 25, meterRegistry);

        scheduler.runBatch();

        verify(financialSyncService).sync(1L);
        verify(financialSyncService).sync(2L);
        verify(financialSyncService).sync(3L);
    }

    @Test
    @DisplayName("성공·실패 인원과 실행 횟수를 지표로 남긴다")
    void recordsMetrics() {
        when(connectedAccountMapper.findUserIdsDueForSync(25, 20)).thenReturn(List.of(1L, 2L, 3L));
        when(financialSyncService.sync(2L)).thenThrow(new RuntimeException("동기화 실패"));
        FinancialSyncBatchScheduler scheduler = new FinancialSyncBatchScheduler(
                connectedAccountMapper, financialSyncService, true, 20, 25, meterRegistry);

        scheduler.runBatch();

        assertEquals(2.0, meterRegistry.get("batch.items")
                .tag("batch", "financial-sync").tag("result", "success").counter().count());
        assertEquals(1.0, meterRegistry.get("batch.items")
                .tag("batch", "financial-sync").tag("result", "failure").counter().count());
        assertEquals(1L, meterRegistry.get("batch.execution")
                .tag("batch", "financial-sync").timer().count());
    }

    @Test
    @DisplayName("enabled=false 면 대상 조회조차 하지 않는다")
    void doesNothingWhenDisabled() {
        FinancialSyncBatchScheduler scheduler = new FinancialSyncBatchScheduler(
                connectedAccountMapper, financialSyncService, false, 20, 25, meterRegistry);

        scheduler.runBatch();

        verifyNoInteractions(connectedAccountMapper, financialSyncService);
    }

    @Test
    @DisplayName("minIntervalMinutes·maxUsersPerTick 를 findUserIdsDueForSync 에 그대로 전달한다")
    void passesThresholdAndMaxUsersPerTickToMapper() {
        when(connectedAccountMapper.findUserIdsDueForSync(25, 5)).thenReturn(List.of());
        FinancialSyncBatchScheduler scheduler = new FinancialSyncBatchScheduler(
                connectedAccountMapper, financialSyncService, true, 5, 25, meterRegistry);

        scheduler.runBatch();

        verify(connectedAccountMapper).findUserIdsDueForSync(25, 5);
    }

    @Test
    @DisplayName("설정된 쿨다운(minIntervalMinutes)을 그대로 넘긴다 — 하드코딩된 값이 아니다")
    void passesConfiguredCooldownToMapper() {
        when(connectedAccountMapper.findUserIdsDueForSync(90, 20)).thenReturn(List.of());
        FinancialSyncBatchScheduler scheduler = new FinancialSyncBatchScheduler(
                connectedAccountMapper, financialSyncService, true, 20, 90, meterRegistry);

        scheduler.runBatch();

        verify(connectedAccountMapper).findUserIdsDueForSync(90, 20);
    }
}