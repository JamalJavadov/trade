package com.tradebot;

import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.BudgetTargetSessionCompletionReason;
import com.tradebot.entity.BudgetTargetSessionStatus;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.repository.BudgetTargetSessionRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.service.BudgetTargetAutoExecutionCloseAllService;
import com.tradebot.service.BudgetTargetAutoExecutionCoordinator;
import com.tradebot.service.BudgetTargetAutoExecutionLifecycleService;
import com.tradebot.service.BudgetTargetAutoExecutionRecoveryService;
import com.tradebot.service.LiveTradePersistenceService;
import com.tradebot.service.LiveTradingReconciliationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BudgetTargetAutoExecutionRecoveryServiceTest {

    @Test
    void recoveryResumesRunningSessionThroughCoordinator() {
        BudgetTargetSessionRepository sessionRepository = mock(BudgetTargetSessionRepository.class);
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        LiveTradePersistenceService persistenceService = mock(LiveTradePersistenceService.class);
        BudgetTargetAutoExecutionLifecycleService lifecycleService = mock(BudgetTargetAutoExecutionLifecycleService.class);
        BudgetTargetAutoExecutionCoordinator coordinator = mock(BudgetTargetAutoExecutionCoordinator.class);
        BudgetTargetAutoExecutionCloseAllService closeAllService = mock(BudgetTargetAutoExecutionCloseAllService.class);
        LiveTradingReconciliationService reconciliationService = mock(LiveTradingReconciliationService.class);

        BudgetTargetSession session = session(BudgetTargetSessionStatus.RUNNING);
        LiveTradeExecution execution = execution(session);

        when(lifecycleService.findActiveSession()).thenReturn(Optional.of(session));
        when(executionRepository.findBySession_IdAndExecutionStatusInOrderByCloseAllPriority(eq(session.getId()), any()))
                .thenReturn(List.of(execution));
        when(sessionRepository.findByIdForUpdate(session.getId())).thenReturn(Optional.of(session));
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        BudgetTargetAutoExecutionRecoveryService service = new BudgetTargetAutoExecutionRecoveryService(
                sessionRepository,
                executionRepository,
                persistenceService,
                lifecycleService,
                coordinator,
                closeAllService,
                reconciliationService);

        service.recoverPersistedSession();

        verify(lifecycleService).appendSessionEvent(eq(session), eq("SESSION_RECOVERY_STARTED"), any(), eq(null), any());
        verify(reconciliationService).reconcileExecution(execution.getId(), "system-recovery", execution.getTraceId(), false);
        verify(persistenceService).refreshSessionRollup(eq(session), any());
        verify(coordinator).reconcileActiveSession();
        verify(lifecycleService).appendSessionEvent(eq(session), eq("SESSION_RECOVERY_COMPLETED"), any(), eq(null), any());
        verify(closeAllService, never()).closeAllIfNeeded(any(), any(), any());
    }

    @Test
    void recoveryResumesStoppingSessionThroughCloseAll() {
        BudgetTargetSessionRepository sessionRepository = mock(BudgetTargetSessionRepository.class);
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        LiveTradePersistenceService persistenceService = mock(LiveTradePersistenceService.class);
        BudgetTargetAutoExecutionLifecycleService lifecycleService = mock(BudgetTargetAutoExecutionLifecycleService.class);
        BudgetTargetAutoExecutionCoordinator coordinator = mock(BudgetTargetAutoExecutionCoordinator.class);
        BudgetTargetAutoExecutionCloseAllService closeAllService = mock(BudgetTargetAutoExecutionCloseAllService.class);
        LiveTradingReconciliationService reconciliationService = mock(LiveTradingReconciliationService.class);

        BudgetTargetSession session = session(BudgetTargetSessionStatus.STOPPING);
        LiveTradeExecution execution = execution(session);

        when(lifecycleService.findActiveSession()).thenReturn(Optional.of(session));
        when(executionRepository.findBySession_IdAndExecutionStatusInOrderByCloseAllPriority(eq(session.getId()), any()))
                .thenReturn(List.of(execution));
        when(sessionRepository.findByIdForUpdate(session.getId())).thenReturn(Optional.of(session));
        when(closeAllService.closeAllIfNeeded(session, "system-recovery", session.getTraceId())).thenReturn(session);

        BudgetTargetAutoExecutionRecoveryService service = new BudgetTargetAutoExecutionRecoveryService(
                sessionRepository,
                executionRepository,
                persistenceService,
                lifecycleService,
                coordinator,
                closeAllService,
                reconciliationService);

        service.recoverPersistedSession();

        verify(closeAllService).closeAllIfNeeded(session, "system-recovery", session.getTraceId());
        verify(coordinator, never()).reconcileActiveSession();
    }

    @Test
    void recoveryFailureMarksSessionAsFatalSyncError() {
        BudgetTargetSessionRepository sessionRepository = mock(BudgetTargetSessionRepository.class);
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        LiveTradePersistenceService persistenceService = mock(LiveTradePersistenceService.class);
        BudgetTargetAutoExecutionLifecycleService lifecycleService = mock(BudgetTargetAutoExecutionLifecycleService.class);
        BudgetTargetAutoExecutionCoordinator coordinator = mock(BudgetTargetAutoExecutionCoordinator.class);
        BudgetTargetAutoExecutionCloseAllService closeAllService = mock(BudgetTargetAutoExecutionCloseAllService.class);
        LiveTradingReconciliationService reconciliationService = mock(LiveTradingReconciliationService.class);

        BudgetTargetSession session = session(BudgetTargetSessionStatus.RUNNING);
        LiveTradeExecution execution = execution(session);

        when(lifecycleService.findActiveSession()).thenReturn(Optional.of(session));
        when(executionRepository.findBySession_IdAndExecutionStatusInOrderByCloseAllPriority(eq(session.getId()), any()))
                .thenReturn(List.of(execution));
        when(sessionRepository.findByIdForUpdate(session.getId())).thenReturn(Optional.of(session));
        doThrow(new IllegalStateException("contradictory exchange state"))
                .when(reconciliationService).reconcileExecution(execution.getId(), "system-recovery", execution.getTraceId(), false);

        BudgetTargetAutoExecutionRecoveryService service = new BudgetTargetAutoExecutionRecoveryService(
                sessionRepository,
                executionRepository,
                persistenceService,
                lifecycleService,
                coordinator,
                closeAllService,
                reconciliationService);

        service.recoverPersistedSession();

        verify(lifecycleService).appendSessionEvent(
                eq(session),
                eq("SESSION_RECOVERY_FAILED"),
                any(),
                eq(BudgetTargetSessionCompletionReason.FATAL_SYNC_ERROR.name()),
                any());
        verify(lifecycleService).failSession(
                eq(session),
                eq(BudgetTargetSessionCompletionReason.FATAL_SYNC_ERROR),
                eq(BudgetTargetSessionCompletionReason.FATAL_SYNC_ERROR.name()),
                any());
    }

    private BudgetTargetSession session(BudgetTargetSessionStatus status) {
        BudgetTargetSession session = new BudgetTargetSession();
        session.setId(UUID.randomUUID());
        session.setStatus(status);
        session.setBudgetAmountUsdt(new BigDecimal("50"));
        session.setTargetProfitUsdt(new BigDecimal("10"));
        session.setTraceId("trace-" + session.getId());
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        session.setConfigSnapshotJson("{}");
        return session;
    }

    private LiveTradeExecution execution(BudgetTargetSession session) {
        LiveTradeExecution execution = new LiveTradeExecution();
        execution.setId(UUID.randomUUID());
        execution.setSession(session);
        execution.setTraceId("trace-execution-" + execution.getId());
        execution.setSymbol("BTCUSDT");
        execution.setSide("BUY");
        execution.setExecutionState(LiveTradeExecutionState.ACTIVE);
        execution.setCreatedAt(Instant.now());
        execution.setUpdatedAt(Instant.now());
        return execution;
    }
}
