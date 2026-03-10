package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.dto.LiveTradeBlockedReasonDTO;
import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.entity.BudgetTargetAuditEventCategory;
import com.tradebot.entity.BudgetTargetAuditSeverity;
import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.BudgetTargetSessionStatus;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.entity.Recommendation;
import com.tradebot.entity.ScanRun;
import com.tradebot.entity.SessionSymbolDecisionAudit;
import com.tradebot.repository.BudgetTargetSessionRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.repository.LiveTradePnlLedgerRepository;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.repository.ScanRunRepository;
import com.tradebot.repository.SessionSymbolDecisionAuditRepository;
import com.tradebot.service.AutoSessionActivePositionGate;
import com.tradebot.service.AutoSessionBudgetAllocator;
import com.tradebot.service.AutoSessionDuplicateConflictGate;
import com.tradebot.service.AutoSessionRecommendationIntakeService;
import com.tradebot.service.BudgetTargetAutoExecutionCloseAllService;
import com.tradebot.service.BudgetTargetAutoExecutionCoordinator;
import com.tradebot.service.BudgetTargetAutoExecutionLifecycleService;
import com.tradebot.service.BudgetTargetSessionStreamPublisher;
import com.tradebot.service.ExchangeSyncSnapshotService;
import com.tradebot.service.LiveTradingBlockerCodes;
import com.tradebot.service.LiveTradingExecutionService;
import com.tradebot.service.LiveTradingPreflightService;
import com.tradebot.service.LiveTradingReconciliationService;
import com.tradebot.service.ScanOrchestrator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BudgetTargetAutoExecutionCoordinatorTest {

    @Test
    void realizedTargetHitTransitionsToStoppingAndStartsCloseAll() {
        BudgetTargetSession session = session(BudgetTargetSessionStatus.RUNNING, new BigDecimal("50"));
        session.setTargetProfitUsdt(new BigDecimal("10"));

        BudgetTargetSession targetReached = session(BudgetTargetSessionStatus.TARGET_REACHED, new BigDecimal("50"));
        targetReached.setId(session.getId());
        targetReached.setTraceId(session.getTraceId());
        targetReached.setCreatedAt(session.getCreatedAt());

        BudgetTargetSession stopping = session(BudgetTargetSessionStatus.STOPPING, new BigDecimal("50"));
        stopping.setId(session.getId());
        stopping.setTraceId(session.getTraceId());
        stopping.setCreatedAt(session.getCreatedAt());

        Fixture fixture = fixture(session, List.of(activeExecution("BTCUSDT", new BigDecimal("5"))));
        when(fixture.ledgerRepository.sumNetPnlBySessionId(session.getId())).thenReturn(new BigDecimal("10.00"));
        when(fixture.lifecycleService.markTargetReached(eq(session), eq("system"), any())).thenReturn(targetReached);
        when(fixture.lifecycleService.requestStop(eq(targetReached), eq(com.tradebot.entity.BudgetTargetSessionCompletionReason.TARGET_REACHED), eq("system"), any()))
                .thenReturn(stopping);
        when(fixture.closeAllService.closeAllIfNeeded(eq(stopping), eq("system"), eq(stopping.getTraceId()))).thenReturn(stopping);

        fixture.coordinator.reconcileActiveSession();

        verify(fixture.lifecycleService).markTargetReached(eq(session), eq("system"), any());
        verify(fixture.lifecycleService).requestStop(
                eq(targetReached),
                eq(com.tradebot.entity.BudgetTargetSessionCompletionReason.TARGET_REACHED),
                eq("system"),
                any());
        verify(fixture.closeAllService).closeAllIfNeeded(eq(stopping), eq("system"), eq(stopping.getTraceId()));
        verify(fixture.scanOrchestrator, never()).runAutoSession(any(), any());
    }

    @Test
    void unrealizedProfitAloneDoesNotTriggerTargetReached() {
        BudgetTargetSession session = session(BudgetTargetSessionStatus.RUNNING, new BigDecimal("50"));
        session.setTargetProfitUsdt(new BigDecimal("10"));
        LiveTradeExecution execution = activeExecution("BTCUSDT", new BigDecimal("5"));
        execution.setExchangeResponseJson("{\"position\":{\"unRealizedProfit\":\"15.00\"}}");

        Fixture fixture = fixture(session, List.of(execution));
        when(fixture.ledgerRepository.sumNetPnlBySessionId(session.getId())).thenReturn(BigDecimal.ZERO);

        fixture.coordinator.reconcileActiveSession();

        verify(fixture.lifecycleService, never()).markTargetReached(any(), any(), any());
        verify(fixture.closeAllService, never()).closeAllIfNeeded(any(), any(), any());
    }

    @Test
    void targetReachedSessionDoesNotOpenNewTradesBeforeCloseAllBegins() {
        BudgetTargetSession session = session(BudgetTargetSessionStatus.TARGET_REACHED, new BigDecimal("50"));
        BudgetTargetSession stopping = session(BudgetTargetSessionStatus.STOPPING, new BigDecimal("50"));
        stopping.setId(session.getId());
        stopping.setTraceId(session.getTraceId());
        stopping.setCreatedAt(session.getCreatedAt());

        Fixture fixture = fixture(session, List.of(activeExecution("BTCUSDT", new BigDecimal("5"))));
        when(fixture.lifecycleService.requestStop(
                eq(session),
                eq(com.tradebot.entity.BudgetTargetSessionCompletionReason.TARGET_REACHED),
                eq("system"),
                any())).thenReturn(stopping);
        when(fixture.closeAllService.closeAllIfNeeded(eq(stopping), eq("system"), eq(stopping.getTraceId()))).thenReturn(stopping);

        fixture.coordinator.reconcileActiveSession();

        verify(fixture.lifecycleService).requestStop(
                eq(session),
                eq(com.tradebot.entity.BudgetTargetSessionCompletionReason.TARGET_REACHED),
                eq("system"),
                any());
        verify(fixture.closeAllService).closeAllIfNeeded(eq(stopping), eq("system"), eq(stopping.getTraceId()));
        verify(fixture.scanOrchestrator, never()).runAutoSession(any(), any());
        verify(fixture.preflightService, never()).evaluate(any(java.util.UUID.class));
        verify(fixture.executionService, never()).executeAutoSession(any(), any(), any(), any(), any());
    }

    @Test
    void armedSessionPromotesToRunningBeforeFirstAutoScanUsingAllocatedSlice() {
        BudgetTargetSession armedSession = session(BudgetTargetSessionStatus.ARMED, new BigDecimal("50"));
        BudgetTargetSession runningSession = session(BudgetTargetSessionStatus.RUNNING, new BigDecimal("50"));
        runningSession.setId(armedSession.getId());
        runningSession.setTraceId(armedSession.getTraceId());
        runningSession.setCreatedAt(armedSession.getCreatedAt());

        Fixture fixture = fixture(armedSession, List.of());
        when(fixture.lifecycleService.attemptRunTransitionIfReady(eq(armedSession), eq("system"), any()))
                .thenReturn(runningSession);

        UUID scanRunId = UUID.randomUUID();
        when(fixture.scanOrchestrator.runAutoSession(any(), any()))
                .thenReturn(new ScanOrchestrator.ScanStartResult(scanRunId, true, "STARTED"));

        fixture.coordinator.reconcileActiveSession();

        ArgumentCaptor<BigDecimal> allocationCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(fixture.scanOrchestrator).runAutoSession(any(), allocationCaptor.capture());
        assertEquals(new BigDecimal("16.66666666"), allocationCaptor.getValue());
        verify(fixture.lifecycleService).setPendingScanRun(eq(runningSession), eq(scanRunId), any());
    }

    @Test
    void activePositionLimitBlocksFourthOpenAndWritesEvent() {
        BudgetTargetSession session = session(BudgetTargetSessionStatus.RUNNING, new BigDecimal("50"));
        Fixture fixture = fixture(session, List.of(
                activeExecution("BTCUSDT", new BigDecimal("5")),
                activeExecution("ETHUSDT", new BigDecimal("5")),
                activeExecution("BNBUSDT", new BigDecimal("5"))));

        fixture.coordinator.reconcileActiveSession();

        verify(fixture.lifecycleService).appendSessionEvent(
                eq(session),
                eq("ACTIVE_LIMIT_REACHED"),
                any(),
                eq("ACTIVE_LIMIT_REACHED"),
                any());
        verify(fixture.scanOrchestrator, never()).runAutoSession(any(), any());
        verifyNoInteractions(fixture.preflightService, fixture.executionService);
    }

    @Test
    void staleExchangeSyncBlocksNewTradesAndWritesExactSessionBlocker() {
        BudgetTargetSession session = session(BudgetTargetSessionStatus.RUNNING, new BigDecimal("50"));
        Fixture fixture = fixture(session, List.of(activeExecution("BTCUSDT", new BigDecimal("5"))));

        com.tradebot.dto.BudgetTargetSyncHealthDTO syncHealth = new com.tradebot.dto.BudgetTargetSyncHealthDTO();
        syncHealth.setStatus(ExchangeSyncSnapshotService.STALE);
        syncHealth.setGateNewTrades(true);
        syncHealth.setGateReasonCode(ExchangeSyncSnapshotService.EXCHANGE_SYNC_STALE);
        syncHealth.setGateReasonMessage("The latest successful Binance sync for active session trades is stale.");
        when(fixture.exchangeSyncSnapshotService.evaluateNewTradeGate(session.getId()))
                .thenReturn(Optional.of(new ExchangeSyncSnapshotService.SyncGateDecision(
                        ExchangeSyncSnapshotService.EXCHANGE_SYNC_STALE,
                        "The latest successful Binance sync for active session trades is stale.",
                        syncHealth)));

        fixture.coordinator.reconcileActiveSession();

        verify(fixture.lifecycleService).appendSessionEvent(
                eq(session),
                eq("SESSION_BLOCKED"),
                eq("The latest successful Binance sync for active session trades is stale."),
                eq(ExchangeSyncSnapshotService.EXCHANGE_SYNC_STALE),
                any());
        verifyNoInteractions(fixture.preflightService, fixture.executionService, fixture.scanOrchestrator);
    }

    @Test
    void budgetExhaustionWritesAuditAndSkipsTradeOpen() {
        BudgetTargetSession session = session(BudgetTargetSessionStatus.RUNNING, new BigDecimal("50"));
        Fixture fixture = fixture(session, List.of(activeExecution("BTCUSDT", new BigDecimal("60"))));

        fixture.coordinator.reconcileActiveSession();

        ArgumentCaptor<SessionSymbolDecisionAudit> auditCaptor = ArgumentCaptor.forClass(SessionSymbolDecisionAudit.class);
        verify(fixture.auditRepository).save(auditCaptor.capture());
        assertEquals("BUDGET_REJECTED", auditCaptor.getValue().getEventType());
        assertEquals("SESSION", auditCaptor.getValue().getSymbol());
        verify(fixture.lifecycleService).appendSessionEvent(
                eq(session),
                eq("BANKROLL_EXHAUSTED"),
                any(),
                eq("BANKROLL_EXHAUSTED"),
                any());
        verify(fixture.lifecycleService).requestStop(
                eq(session),
                eq(com.tradebot.entity.BudgetTargetSessionCompletionReason.BUDGET_EXHAUSTED),
                eq("system"),
                any());
        verify(fixture.closeAllService).closeAllIfNeeded(eq(session), eq("system"), eq(session.getTraceId()));
        verify(fixture.executionService, never()).executeAutoSession(any(), any(), any(), any(), any());
        verify(fixture.scanOrchestrator, never()).runAutoSession(any(), any());
    }

    @Test
    void readOnlyToggleStopsSessionAndStartsCloseAll() {
        BudgetTargetSession session = session(BudgetTargetSessionStatus.RUNNING, new BigDecimal("50"));
        BudgetTargetSession stopping = session(BudgetTargetSessionStatus.STOPPING, new BigDecimal("50"));
        stopping.setId(session.getId());
        stopping.setTraceId(session.getTraceId());
        stopping.setCreatedAt(session.getCreatedAt());

        Fixture fixture = fixture(session, List.of(activeExecution("BTCUSDT", new BigDecimal("5"))));
        fixture.snapshot.getBudgetTargetAutoExecution().setReadOnly(true);
        when(fixture.lifecycleService.evaluateRuntimeStop(eq(fixture.snapshot), eq(session), eq(true)))
                .thenReturn(Optional.of(new BudgetTargetAutoExecutionLifecycleService.StopDecision(
                        com.tradebot.entity.BudgetTargetSessionCompletionReason.READ_ONLY_ENABLED,
                        "Budget-target auto-execution entered READ-ONLY mode while the session was active.",
                        java.util.Map.of("configPath", "budgetTargetAutoExecution.readOnly"))));
        when(fixture.lifecycleService.requestStop(
                eq(session),
                eq(com.tradebot.entity.BudgetTargetSessionCompletionReason.READ_ONLY_ENABLED),
                eq("system"),
                any())).thenReturn(stopping);
        when(fixture.closeAllService.closeAllIfNeeded(eq(stopping), eq("system"), eq(stopping.getTraceId()))).thenReturn(stopping);

        fixture.coordinator.reconcileActiveSession();

        verify(fixture.lifecycleService).requestStop(
                eq(session),
                eq(com.tradebot.entity.BudgetTargetSessionCompletionReason.READ_ONLY_ENABLED),
                eq("system"),
                any());
        verify(fixture.closeAllService).closeAllIfNeeded(eq(stopping), eq("system"), eq(stopping.getTraceId()));
        verify(fixture.scanOrchestrator, never()).runAutoSession(any(), any());
    }

    @Test
    void killSwitchStopsSessionAndStartsCloseAll() {
        BudgetTargetSession session = session(BudgetTargetSessionStatus.RUNNING, new BigDecimal("50"));
        BudgetTargetSession stopping = session(BudgetTargetSessionStatus.STOPPING, new BigDecimal("50"));
        stopping.setId(session.getId());
        stopping.setTraceId(session.getTraceId());
        stopping.setCreatedAt(session.getCreatedAt());

        Fixture fixture = fixture(session, List.of(activeExecution("BTCUSDT", new BigDecimal("5"))));
        fixture.snapshot.getBudgetTargetAutoExecution().setKillSwitch(true);
        when(fixture.lifecycleService.evaluateRuntimeStop(eq(fixture.snapshot), eq(session), eq(true)))
                .thenReturn(Optional.of(new BudgetTargetAutoExecutionLifecycleService.StopDecision(
                        com.tradebot.entity.BudgetTargetSessionCompletionReason.KILL_SWITCH,
                        "Control Center kill switch forced the budget-target auto-execution session to stop.",
                        java.util.Map.of("configPath", "budgetTargetAutoExecution.killSwitch"))));
        when(fixture.lifecycleService.requestStop(
                eq(session),
                eq(com.tradebot.entity.BudgetTargetSessionCompletionReason.KILL_SWITCH),
                eq("system"),
                any())).thenReturn(stopping);
        when(fixture.closeAllService.closeAllIfNeeded(eq(stopping), eq("system"), eq(stopping.getTraceId()))).thenReturn(stopping);

        fixture.coordinator.reconcileActiveSession();

        verify(fixture.lifecycleService).requestStop(
                eq(session),
                eq(com.tradebot.entity.BudgetTargetSessionCompletionReason.KILL_SWITCH),
                eq("system"),
                any());
        verify(fixture.closeAllService).closeAllIfNeeded(eq(stopping), eq("system"), eq(stopping.getTraceId()));
        verify(fixture.scanOrchestrator, never()).runAutoSession(any(), any());
    }

    @Test
    void duplicateLiveExecutionConflictRejectsRecommendationWithoutOpeningTrade() {
        BudgetTargetSession session = session(BudgetTargetSessionStatus.RUNNING, new BigDecimal("75"));
        Fixture fixture = fixture(session, List.of());
        Recommendation recommendation = recommendation("BTCUSDT");
        when(fixture.recommendationRepository.findAllByOrderByCreatedAtDesc(any()))
                .thenReturn(new PageImpl<>(List.of(recommendation)));
        when(fixture.preflightService.evaluate(recommendation.getId())).thenReturn(executablePreflight());

        LiveTradeExecution conflictingExecution = activeExecution("BTCUSDT", new BigDecimal("5"));
        when(fixture.executionRepository.findFirstBySymbolAndExecutionStatusInOrderByCreatedAtDesc(eq("BTCUSDT"), any()))
                .thenReturn(Optional.of(conflictingExecution));
        UUID scanRunId = UUID.randomUUID();
        when(fixture.scanOrchestrator.runAutoSession(any(), any()))
                .thenReturn(new ScanOrchestrator.ScanStartResult(scanRunId, true, "STARTED"));

        fixture.coordinator.reconcileActiveSession();

        verify(fixture.executionService, never()).executeAutoSession(any(), any(), any(), any(), any());
        verify(fixture.lifecycleService).appendSessionEvent(
                eq(session),
                eq("RECOMMENDATION_SKIPPED"),
                eq("Symbol already has an active live execution."),
                eq("ACTIVE_SYMBOL_CONFLICT"),
                any());
        verify(fixture.scanOrchestrator).runAutoSession(any(), any());
    }

    @Test
    void exchangeFilterValidationFailuresRejectRecommendationWithStructuredCode() {
        BudgetTargetSession session = session(BudgetTargetSessionStatus.RUNNING, new BigDecimal("75"));
        Fixture fixture = fixture(session, List.of());
        Recommendation recommendation = recommendation("ETHUSDT");
        when(fixture.recommendationRepository.findAllByOrderByCreatedAtDesc(any()))
                .thenReturn(new PageImpl<>(List.of(recommendation)));

        LiveTradingPreflightDTO exchangeBlocked = new LiveTradingPreflightDTO();
        exchangeBlocked.setAllowed(false);
        exchangeBlocked.setExecutable(false);
        exchangeBlocked.getSummary().setPrimaryBlockerCode(LiveTradingBlockerCodes.EXCHANGE_FILTER_INVALID);
        exchangeBlocked.getSummary().setPrimaryBlockerMessage("Entry quantity is below Binance minimum quantity.");
        LiveTradeBlockedReasonDTO blocker = new LiveTradeBlockedReasonDTO();
        blocker.setCode(LiveTradingBlockerCodes.EXCHANGE_FILTER_INVALID);
        blocker.setMessage("Entry quantity is below Binance minimum quantity.");
        exchangeBlocked.getBlockedReasons().add(blocker);
        when(fixture.preflightService.evaluate(recommendation.getId())).thenReturn(exchangeBlocked);

        UUID scanRunId = UUID.randomUUID();
        when(fixture.scanOrchestrator.runAutoSession(any(), any()))
                .thenReturn(new ScanOrchestrator.ScanStartResult(scanRunId, true, "STARTED"));

        fixture.coordinator.reconcileActiveSession();

        ArgumentCaptor<SessionSymbolDecisionAudit> auditCaptor = ArgumentCaptor.forClass(SessionSymbolDecisionAudit.class);
        verify(fixture.auditRepository, org.mockito.Mockito.times(2)).save(auditCaptor.capture());
        List<SessionSymbolDecisionAudit> audits = auditCaptor.getAllValues();
        assertEquals("BUDGET_ALLOCATED", audits.get(0).getEventType());
        assertEquals("INTAKE_REJECTED", audits.get(1).getEventType());
        org.assertj.core.api.Assertions.assertThat(audits.get(1).getAfterJson()).contains(LiveTradingBlockerCodes.EXCHANGE_FILTER_INVALID);
        verify(fixture.executionService, never()).executeAutoSession(any(), any(), any(), any(), any());
        verify(fixture.lifecycleService).appendSessionEvent(
                eq(session),
                eq("RECOMMENDATION_SKIPPED"),
                eq("Entry quantity is below Binance minimum quantity."),
                eq(LiveTradingBlockerCodes.EXCHANGE_FILTER_INVALID),
                any());
    }

    @Test
    void staleRecommendationWritesAllocationAndRejectAuditsWithoutExecuting() {
        BudgetTargetSession session = session(BudgetTargetSessionStatus.RUNNING, new BigDecimal("50"));
        Fixture fixture = fixture(session, List.of());
        Recommendation recommendation = recommendation("BTCUSDT");
        when(fixture.recommendationRepository.findAllByOrderByCreatedAtDesc(any()))
                .thenReturn(new PageImpl<>(List.of(recommendation)));

        LiveTradingPreflightDTO stalePreflight = new LiveTradingPreflightDTO();
        stalePreflight.setAllowed(false);
        stalePreflight.setExecutable(false);
        stalePreflight.getSummary().setPrimaryBlockerCode(LiveTradingBlockerCodes.RECOMMENDATION_STALE);
        stalePreflight.getSummary().setPrimaryBlockerMessage("Recommendation is stale for live execution.");
        LiveTradeBlockedReasonDTO blocker = new LiveTradeBlockedReasonDTO();
        blocker.setCode(LiveTradingBlockerCodes.RECOMMENDATION_STALE);
        blocker.setMessage("Recommendation is stale for live execution.");
        stalePreflight.getBlockedReasons().add(blocker);
        when(fixture.preflightService.evaluate(recommendation.getId())).thenReturn(stalePreflight);

        UUID scanRunId = UUID.randomUUID();
        when(fixture.scanOrchestrator.runAutoSession(any(), any()))
                .thenReturn(new ScanOrchestrator.ScanStartResult(scanRunId, true, "STARTED"));

        fixture.coordinator.reconcileActiveSession();

        ArgumentCaptor<SessionSymbolDecisionAudit> auditCaptor = ArgumentCaptor.forClass(SessionSymbolDecisionAudit.class);
        verify(fixture.auditRepository, org.mockito.Mockito.times(2)).save(auditCaptor.capture());
        List<SessionSymbolDecisionAudit> audits = auditCaptor.getAllValues();
        assertEquals("BUDGET_ALLOCATED", audits.get(0).getEventType());
        assertEquals(BudgetTargetAuditEventCategory.TRADE, audits.get(0).getEventCategory());
        assertEquals(BudgetTargetAuditSeverity.INFO, audits.get(0).getSeverity());
        assertEquals("system", audits.get(0).getActor());
        org.assertj.core.api.Assertions.assertThat(audits.get(0).getBeforeJson()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(audits.get(0).getAfterJson()).contains("allocatedSliceUsdt");
        assertEquals("INTAKE_REJECTED", audits.get(1).getEventType());
        assertEquals(recommendation.getId(), audits.get(1).getRecommendation().getId());
        assertEquals(BudgetTargetAuditSeverity.WARN, audits.get(1).getSeverity());
        org.assertj.core.api.Assertions.assertThat(audits.get(1).getAfterJson()).contains(LiveTradingBlockerCodes.RECOMMENDATION_STALE);
        verify(fixture.executionService, never()).executeAutoSession(any(), any(), any(), any(), any());
        verify(fixture.lifecycleService).appendSessionEvent(
                eq(session),
                eq("RECOMMENDATION_SKIPPED"),
                any(),
                eq(LiveTradingBlockerCodes.RECOMMENDATION_STALE),
                any());
        verify(fixture.lifecycleService, never()).recordExecutionFailure(any(), any(), any(), any(), any());
        verify(fixture.scanOrchestrator).runAutoSession(any(), any());
        verify(fixture.lifecycleService).setPendingScanRun(eq(session), eq(scanRunId), any());
    }

    @Test
    void failedExecutionCountsAsExecutionFailureAndSurfacesExactReason() {
        BudgetTargetSession session = session(BudgetTargetSessionStatus.RUNNING, new BigDecimal("50"));
        Fixture fixture = fixture(session, List.of());
        Recommendation recommendation = recommendation("BTCUSDT");
        when(fixture.recommendationRepository.findAllByOrderByCreatedAtDesc(any()))
                .thenReturn(new PageImpl<>(List.of(recommendation)));
        when(fixture.preflightService.evaluate(recommendation.getId())).thenReturn(executablePreflight());

        LiveTradeExecutionDTO failedExecution = new LiveTradeExecutionDTO();
        failedExecution.setId(UUID.randomUUID());
        failedExecution.setExecutionState("FAILED");
        failedExecution.setErrorCode(LiveTradingBlockerCodes.BINANCE_REJECTED);
        failedExecution.setErrorMessage("Entry rejected by Binance.");
        when(fixture.executionService.executeAutoSession(any(), any(), any(), any(), any()))
                .thenReturn(failedExecution);

        UUID scanRunId = UUID.randomUUID();
        when(fixture.scanOrchestrator.runAutoSession(any(), any()))
                .thenReturn(new ScanOrchestrator.ScanStartResult(scanRunId, true, "STARTED"));

        fixture.coordinator.reconcileActiveSession();

        verify(fixture.lifecycleService).recordExecutionFailure(
                eq(session),
                isNull(),
                eq(LiveTradingBlockerCodes.BINANCE_REJECTED),
                eq("Entry rejected by Binance."),
                any());
        verify(fixture.lifecycleService).appendSessionEvent(
                eq(session),
                eq("RECOMMENDATION_SKIPPED"),
                eq("Entry rejected by Binance."),
                eq(LiveTradingBlockerCodes.BINANCE_REJECTED),
                any());
    }

    private Fixture fixture(BudgetTargetSession session, List<LiveTradeExecution> activeExecutions) {
        ControlCenterSettingsProvider settingsProvider = mock(ControlCenterSettingsProvider.class);
        BudgetTargetSessionRepository sessionRepository = mock(BudgetTargetSessionRepository.class);
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        LiveTradePnlLedgerRepository ledgerRepository = mock(LiveTradePnlLedgerRepository.class);
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        ScanRunRepository scanRunRepository = mock(ScanRunRepository.class);
        SessionSymbolDecisionAuditRepository auditRepository = mock(SessionSymbolDecisionAuditRepository.class);
        ScanOrchestrator scanOrchestrator = mock(ScanOrchestrator.class);
        LiveTradingExecutionService executionService = mock(LiveTradingExecutionService.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        LiveTradingReconciliationService reconciliationService = mock(LiveTradingReconciliationService.class);
        BudgetTargetAutoExecutionLifecycleService lifecycleService = mock(BudgetTargetAutoExecutionLifecycleService.class);
        BudgetTargetAutoExecutionCloseAllService closeAllService = mock(BudgetTargetAutoExecutionCloseAllService.class);
        BudgetTargetSessionStreamPublisher streamPublisher = mock(BudgetTargetSessionStreamPublisher.class);
        ExchangeSyncSnapshotService exchangeSyncSnapshotService = mock(ExchangeSyncSnapshotService.class);

        AutoSessionBudgetAllocator budgetAllocator = new AutoSessionBudgetAllocator();
        AutoSessionActivePositionGate activePositionGate = new AutoSessionActivePositionGate();
        AutoSessionDuplicateConflictGate duplicateConflictGate = new AutoSessionDuplicateConflictGate(executionRepository);
        AutoSessionRecommendationIntakeService recommendationIntakeService = new AutoSessionRecommendationIntakeService(
                recommendationRepository,
                auditRepository);

        ControlCenterConfig snapshot = new ControlCenterConfig();
        snapshot.getScan().setSafeMode(false);
        snapshot.getBudgetTargetAutoExecution().setEnabled(true);
        snapshot.getBudgetTargetAutoExecution().setArmed(true);

        doNothing().when(lifecycleService).syncRuntimeWithControlCenter();
        when(lifecycleService.findActiveSession()).thenReturn(Optional.of(session));
        when(lifecycleService.isSessionTimedOut(eq(session), any())).thenReturn(false);
        when(lifecycleService.evaluateRuntimeStop(any(), eq(session), anyBoolean())).thenReturn(Optional.empty());
        when(ledgerRepository.sumNetPnlBySessionId(session.getId())).thenReturn(BigDecimal.ZERO);
        when(executionRepository.findBySession_IdAndExecutionStatusInOrderByCreatedAtAsc(eq(session.getId()), any()))
                .thenReturn(activeExecutions);
        when(executionRepository.findBySession_IdOrderByCreatedAtDesc(session.getId())).thenReturn(activeExecutions);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(settingsProvider.getConfigSnapshot()).thenReturn(snapshot);
        when(settingsProvider.can("live.execution.enabled")).thenReturn(true);
        when(settingsProvider.isLiveExecutionReadOnly()).thenReturn(false);
        when(recommendationRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(new PageImpl<>(List.of()));
        when(auditRepository.existsBySession_IdAndRecommendation_IdAndEventTypeIn(any(), any(), any())).thenReturn(false);
        when(lifecycleService.updateSessionRollup(any(), any(), any(), any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(session);
        when(lifecycleService.setVisibleFailure(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lifecycleService.recordExecutionFailure(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(lifecycleService.appendSessionEvent(any(), any(), any(), any(), any())).thenReturn(session);
        when(lifecycleService.appendSessionEvent(any(), any(LiveTradeExecution.class), any(), any(), any(), any())).thenReturn(session);
        when(lifecycleService.clearPendingScanRun(any(), any(), any(), any())).thenReturn(session);
        when(lifecycleService.setPendingScanRun(any(), any(), any())).thenReturn(session);
        when(lifecycleService.requestStop(any(), any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lifecycleService.markTargetReached(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(closeAllService.closeAllIfNeeded(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(auditRepository.save(any(SessionSymbolDecisionAudit.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(exchangeSyncSnapshotService.evaluateNewTradeGate(session.getId())).thenReturn(Optional.empty());
        when(exchangeSyncSnapshotService.isSyncHealthGateCode(any())).thenReturn(false);

        BudgetTargetAutoExecutionCoordinator coordinator = new BudgetTargetAutoExecutionCoordinator(
                settingsProvider,
                sessionRepository,
                executionRepository,
                ledgerRepository,
                recommendationRepository,
                scanRunRepository,
                auditRepository,
                scanOrchestrator,
                executionService,
                preflightService,
                reconciliationService,
                lifecycleService,
                closeAllService,
                budgetAllocator,
                activePositionGate,
                duplicateConflictGate,
                recommendationIntakeService,
                streamPublisher,
                exchangeSyncSnapshotService,
                new ObjectMapper().findAndRegisterModules());
        return new Fixture(coordinator,
                snapshot,
                lifecycleService,
                closeAllService,
                ledgerRepository,
                recommendationRepository,
                auditRepository,
                executionRepository,
                executionService,
                preflightService,
                scanOrchestrator,
                exchangeSyncSnapshotService);
    }

    private BudgetTargetSession session(BudgetTargetSessionStatus status, BigDecimal budgetAmountUsdt) {
        BudgetTargetSession session = new BudgetTargetSession();
        session.setId(UUID.randomUUID());
        session.setStatus(status);
        session.setBudgetAmountUsdt(budgetAmountUsdt);
        session.setTargetProfitUsdt(new BigDecimal("10"));
        session.setRealizedNetPnlUsdt(BigDecimal.ZERO);
        session.setUnrealizedNetPnlUsdt(BigDecimal.ZERO);
        session.setMaxConcurrentPositions(3);
        session.setTraceId("trace-" + session.getId());
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        session.setConfigSnapshotJson("{}");
        return session;
    }

    private Recommendation recommendation(String symbol) {
        Recommendation recommendation = new Recommendation();
        recommendation.setId(UUID.randomUUID());
        recommendation.setSymbol(symbol);
        recommendation.setSide("BUY");
        recommendation.setStatus("NEW");
        recommendation.setCreatedAt(Instant.now());
        ScanRun scanRun = new ScanRun();
        scanRun.setId(UUID.randomUUID());
        recommendation.setScanRun(scanRun);
        return recommendation;
    }

    private LiveTradeExecution activeExecution(String symbol, BigDecimal reservedMarginUsdt) {
        LiveTradeExecution execution = new LiveTradeExecution();
        execution.setId(UUID.randomUUID());
        execution.setSymbol(symbol);
        execution.setReservedMarginUsdt(reservedMarginUsdt);
        execution.setExecutionStatus(LiveTradeExecutionState.ACTIVE);
        execution.setCreatedAt(Instant.now());
        execution.setUpdatedAt(Instant.now());
        execution.setActualFilledQty(new BigDecimal("0.010"));
        return execution;
    }

    private LiveTradingPreflightDTO executablePreflight() {
        LiveTradingPreflightDTO preflight = new LiveTradingPreflightDTO();
        preflight.setAllowed(true);
        preflight.setExecutable(true);
        preflight.getExchangeValidation().setEntryNotionalUsdt(new BigDecimal("50"));
        preflight.getExchangeValidation().setLeverage(5);
        return preflight;
    }

    private record Fixture(
            BudgetTargetAutoExecutionCoordinator coordinator,
            ControlCenterConfig snapshot,
            BudgetTargetAutoExecutionLifecycleService lifecycleService,
            BudgetTargetAutoExecutionCloseAllService closeAllService,
            LiveTradePnlLedgerRepository ledgerRepository,
            RecommendationRepository recommendationRepository,
            SessionSymbolDecisionAuditRepository auditRepository,
            LiveTradeExecutionRepository executionRepository,
            LiveTradingExecutionService executionService,
            LiveTradingPreflightService preflightService,
            ScanOrchestrator scanOrchestrator,
            ExchangeSyncSnapshotService exchangeSyncSnapshotService) {
    }
}
