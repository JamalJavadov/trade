package com.tradebot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.BudgetTargetSessionCompletionReason;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class BudgetTargetAutoExecutionCoordinator {

    private static final String RUN_STATUS_STARTED = "STARTED";
    private static final String RUN_STATUS_FINISHED = "FINISHED";
    private static final String EVENT_BUDGET_ALLOCATED = "BUDGET_ALLOCATED";
    private static final String EVENT_BUDGET_REJECTED = "BUDGET_REJECTED";
    private static final String EVENT_INTAKE_ACCEPTED = "INTAKE_ACCEPTED";
    private static final String EVENT_INTAKE_REJECTED = "INTAKE_REJECTED";
    private static final String EVENT_NO_RECOMMENDATION = "NO_RECOMMENDATION";
    private static final String EVENT_ACTIVE_LIMIT_REACHED = "ACTIVE_LIMIT_REACHED";
    private static final String EVENT_SCAN_FAILED = "SCAN_FAILED";
    private static final String EVENT_SESSION_SYMBOL = "SESSION";
    private static final String RECOMMENDATION_ALREADY_EVALUATED = "RECOMMENDATION_ALREADY_EVALUATED";
    private static final String RESERVED_MARGIN_UNAVAILABLE = "RESERVED_MARGIN_UNAVAILABLE";
    private static final String ALLOCATED_SLICE_EXCEEDED = "ALLOCATED_SLICE_EXCEEDED";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final List<LiveTradeExecutionState> ACTIVE_EXECUTION_STATES = Arrays.stream(LiveTradeExecutionState.values())
            .filter(LiveTradeExecutionState::isActive)
            .toList();

    private final ControlCenterSettingsProvider controlCenterSettingsProvider;
    private final BudgetTargetSessionRepository budgetTargetSessionRepository;
    private final LiveTradeExecutionRepository liveTradeExecutionRepository;
    private final LiveTradePnlLedgerRepository liveTradePnlLedgerRepository;
    private final RecommendationRepository recommendationRepository;
    private final ScanRunRepository scanRunRepository;
    private final SessionSymbolDecisionAuditRepository sessionSymbolDecisionAuditRepository;
    private final ScanOrchestrator scanOrchestrator;
    private final LiveTradingExecutionService liveTradingExecutionService;
    private final LiveTradingPreflightService liveTradingPreflightService;
    private final LiveTradingReconciliationService liveTradingReconciliationService;
    private final BudgetTargetAutoExecutionLifecycleService lifecycleService;
    private final BudgetTargetAutoExecutionCloseAllService closeAllService;
    private final AutoSessionBudgetAllocator budgetAllocator;
    private final AutoSessionActivePositionGate activePositionGate;
    private final AutoSessionDuplicateConflictGate duplicateConflictGate;
    private final AutoSessionRecommendationIntakeService recommendationIntakeService;
    private final BudgetTargetSessionStreamPublisher sessionStreamPublisher;
    private final ExchangeSyncSnapshotService exchangeSyncSnapshotService;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(
            fixedDelayString = "${tradebot.budget-target-auto-execution.coordinator.fixed-delay-ms:5000}",
            initialDelayString = "${tradebot.budget-target-auto-execution.coordinator.initial-delay-ms:20000}")
    public void tick() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            reconcileActiveSession();
        } finally {
            running.set(false);
        }
    }

    public void reconcileActiveSession() {
        lifecycleService.syncRuntimeWithControlCenter();
        BudgetTargetSession session = lifecycleService.findActiveSession().orElse(null);
        if (session == null) {
            return;
        }

        ControlCenterConfig configSnapshot = controlCenterSettingsProvider.getConfigSnapshot();
        ControlCenterConfig.BudgetTargetAutoExecution autoConfig = configSnapshot.getBudgetTargetAutoExecution();
        List<LiveTradeExecution> activeExecutions = liveTradeExecutionRepository
                .findBySession_IdAndExecutionStatusInOrderByCreatedAtAsc(session.getId(), ACTIVE_EXECUTION_STATES);
        BigDecimal realizedNetPnlUsdt = sumRealizedNetPnl(session.getId());
        BigDecimal unrealizedNetPnlUsdt = sumUnrealizedNetPnl(activeExecutions);
        int openedPositionsTotal = countOpenedPositions(session.getId());
        int closedPositionsTotal = countClosedPositions(session.getId());
        lifecycleService.updateSessionRollup(
                session,
                realizedNetPnlUsdt,
                unrealizedNetPnlUsdt,
                activeExecutions.size(),
                openedPositionsTotal,
                closedPositionsTotal);
        session = budgetTargetSessionRepository.findById(session.getId()).orElse(session);

        if (lifecycleService.isSessionTimedOut(session, autoConfig)
                && session.getStatus() != BudgetTargetSessionStatus.STOPPING) {
            session = lifecycleService.requestStop(session,
                    BudgetTargetSessionCompletionReason.SESSION_TIMEOUT,
                    "system",
                    "Budget-target auto-execution session timed out and is stopping.");
        }

        if (session.getStatus() == BudgetTargetSessionStatus.RUNNING
                && realizedNetPnlUsdt.compareTo(session.getTargetProfitUsdt()) >= 0) {
            session = lifecycleService.markTargetReached(session,
                    "system",
                    "Final target realized net PnL was reached.");
            session = lifecycleService.requestStop(session,
                    BudgetTargetSessionCompletionReason.TARGET_REACHED,
                    "system",
                    "Target was reached and the session is transitioning into STOPPING.");
        }

        if (session.getStatus() == BudgetTargetSessionStatus.TARGET_REACHED) {
            session = lifecycleService.requestStop(session,
                    BudgetTargetSessionCompletionReason.TARGET_REACHED,
                    "system",
                    "Target-reached session is transitioning into STOPPING.");
        }

        if (session.getStatus() == BudgetTargetSessionStatus.STOPPING) {
            closeAllService.closeAllIfNeeded(session, "system", session.getTraceId());
            return;
        }
        if (session.getStatus() == BudgetTargetSessionStatus.ARMED) {
            session = lifecycleService.attemptRunTransitionIfReady(
                    session,
                    "system",
                    "Budget-target auto-session passed current runtime checks and moved to RUNNING.");
            if (session.getStatus() != BudgetTargetSessionStatus.RUNNING) {
                return;
            }
        }
        if (session.getStatus() != BudgetTargetSessionStatus.RUNNING) {
            return;
        }

        BudgetTargetAutoExecutionLifecycleService.StopDecision runtimeStop = lifecycleService
                .evaluateRuntimeStop(configSnapshot, session, true)
                .orElse(null);
        if (runtimeStop != null) {
            session = lifecycleService.requestStop(
                    session,
                    runtimeStop.reason(),
                    "system",
                    runtimeStop.message());
            if (session.getStatus() == BudgetTargetSessionStatus.STOPPING) {
                closeAllService.closeAllIfNeeded(session, "system", session.getTraceId());
            }
            return;
        }

        ActivePositionGateDecision positionGateDecision = activePositionGate.evaluate(session, activeExecutions.size());
        if (!positionGateDecision.allowed()) {
            lifecycleService.setVisibleFailure(
                    session,
                    positionGateDecision.reasonCode(),
                    positionGateDecision.reasonMessage());
            lifecycleService.appendSessionEvent(session,
                    EVENT_ACTIVE_LIMIT_REACHED,
                    positionGateDecision.reasonMessage(),
                    positionGateDecision.reasonCode(),
                    positionGateDecision.toPayload());
            return;
        }

        ExchangeSyncSnapshotService.SyncGateDecision syncGateDecision = exchangeSyncSnapshotService
                .evaluateNewTradeGate(session.getId())
                .orElse(null);
        if (syncGateDecision != null) {
            lifecycleService.appendSessionEvent(session,
                    "SESSION_BLOCKED",
                    syncGateDecision.reasonMessage(),
                    syncGateDecision.reasonCode(),
                    payloadOf(
                            "source", "SYNC_HEALTH",
                            "syncHealth", syncGateDecision.syncHealth()));
            return;
        }
        clearRecoveredSyncFailure(session);

        BudgetAllocationDecision allocationDecision = budgetAllocator.allocate(session, activeExecutions, positionGateDecision);
        if (!allocationDecision.allowed()) {
            lifecycleService.setVisibleFailure(
                    session,
                    allocationDecision.reasonCode(),
                    allocationDecision.reasonMessage());
            auditBudgetDecision(session,
                    null,
                    null,
                    allocationDecision,
                    allocationDecision.reasonMessage(),
                    payloadOf("source", "SESSION"));
            lifecycleService.appendSessionEvent(session,
                    "BANKROLL_EXHAUSTED",
                    allocationDecision.reasonMessage(),
                    allocationDecision.reasonCode(),
                    allocationDecision.toPayload());
            session = lifecycleService.requestStop(session,
                    BudgetTargetSessionCompletionReason.BUDGET_EXHAUSTED,
                    "system",
                    "Budget allocator reported no spendable bankroll for further auto-execution. Session is stopping.");
            closeAllService.closeAllIfNeeded(session, "system", session.getTraceId());
            return;
        }

        CandidateProcessResult result = session.getPendingScanRunId() != null
                ? handlePendingScan(session, allocationDecision)
                : processLatestRecommendation(session, allocationDecision);
        if (result.awaitingScan() || result.accepted()) {
            return;
        }

        requestSessionOwnedScan(session, allocationDecision, !result.budgetAuditWritten());
    }

    private CandidateProcessResult processLatestRecommendation(BudgetTargetSession session,
            BudgetAllocationDecision allocationDecision) {
        Recommendation recommendation = recommendationIntakeService.findNewestUnauditedRecommendation(session.getId())
                .orElse(null);
        if (recommendation == null) {
            return CandidateProcessResult.rejectedResult(false);
        }
        return processRecommendation(session, null, recommendation, "LATEST_RECOMMENDATION", allocationDecision);
    }

    private CandidateProcessResult handlePendingScan(BudgetTargetSession session,
            BudgetAllocationDecision allocationDecision) {
        ScanRun scanRun = scanRunRepository.findById(session.getPendingScanRunId()).orElse(null);
        if (scanRun == null) {
            lifecycleService.clearPendingScanRun(session,
                    "SCAN_CLEARED",
                    "Pending scan reference disappeared before reconciliation.",
                    payloadOf("scanRunId", session.getPendingScanRunId()));
            return CandidateProcessResult.rejectedResult(false);
        }
        if (RUN_STATUS_STARTED.equalsIgnoreCase(scanRun.getStatus())) {
            return CandidateProcessResult.awaitingScanResult();
        }
        if (!RUN_STATUS_FINISHED.equalsIgnoreCase(scanRun.getStatus())) {
            lifecycleService.clearPendingScanRun(session,
                    EVENT_SCAN_FAILED,
                    "Pending scan finished without a usable recommendation.",
                    payloadOf(
                            "scanRunId", scanRun.getId(),
                            "status", scanRun.getStatus(),
                            "errorCode", scanRun.getErrorCode(),
                            "notes", scanRun.getNotes()));
            lifecycleService.appendSessionEvent(session,
                    EVENT_SCAN_FAILED,
                    "Pending scan finished without a usable recommendation.",
                    EVENT_SCAN_FAILED,
                    payloadOf(
                            "scanRunId", scanRun.getId(),
                            "status", scanRun.getStatus(),
                            "errorCode", scanRun.getErrorCode(),
                            "notes", scanRun.getNotes()));
            return CandidateProcessResult.rejectedResult(false);
        }

        Recommendation recommendation = recommendationRepository.findFirstByScanRunIdOrderByCreatedAtDesc(scanRun.getId())
                .orElse(null);
        lifecycleService.clearPendingScanRun(session,
                "SCAN_FINISHED",
                "Pending auto-session scan finished.",
                payloadOf("scanRunId", scanRun.getId(), "triggerType", scanRun.getTriggerType()));
        if (recommendation == null) {
            lifecycleService.appendSessionEvent(session,
                    EVENT_NO_RECOMMENDATION,
                    "Scan finished without an eligible recommendation.",
                    EVENT_NO_RECOMMENDATION,
                    payloadOf("scanRunId", scanRun.getId()));
            return CandidateProcessResult.rejectedResult(false);
        }
        return processRecommendation(session, scanRun, recommendation, "PENDING_SCAN", allocationDecision);
    }

    private CandidateProcessResult processRecommendation(BudgetTargetSession session,
            ScanRun scanRun,
            Recommendation recommendation,
            String source,
            BudgetAllocationDecision allocationDecision) {
        if (recommendation == null) {
            return CandidateProcessResult.rejectedResult(false);
        }
        if (recommendationIntakeService.alreadyEvaluated(session.getId(), recommendation.getId())) {
            lifecycleService.appendSessionEvent(session,
                    "RECOMMENDATION_SKIPPED",
                    "Recommendation was already evaluated earlier in this session.",
                    RECOMMENDATION_ALREADY_EVALUATED,
                    payloadOf(
                            "recommendationId", recommendation.getId(),
                            "symbol", recommendation.getSymbol(),
                            "source", source));
            return CandidateProcessResult.rejectedResult(false);
        }

        auditBudgetDecision(session,
                scanRun,
                recommendation,
                allocationDecision,
                "Budget allocator approved a per-trade slice for candidate intake.",
                payloadOf("source", source));

        LiveTradingPreflightDTO preflight = liveTradingPreflightService.evaluate(recommendation.getId());
        if (!preflight.isExecutable()) {
            String blockerCode = firstNonBlank(
                    preflight.getSummary().getPrimaryBlockerCode(),
                    firstBlockedCode(preflight));
            String blockerMessage = firstNonBlank(
                    preflight.getSummary().getPrimaryBlockerMessage(),
                    firstBlockedMessage(preflight),
                    "Recommendation preflight failed.");
            rejectRecommendation(session,
                    scanRun,
                    recommendation,
                    blockerCode,
                    blockerMessage,
                    payloadOf(
                            "source", source,
                            "allocation", allocationDecision.toPayload(),
                            "preflight", preflight));
            return CandidateProcessResult.rejectedResult(true);
        }

        DuplicateConflictDecision duplicateConflictDecision = duplicateConflictGate.evaluate(recommendation);
        if (!duplicateConflictDecision.allowed()) {
            rejectRecommendation(session,
                    scanRun,
                    recommendation,
                    duplicateConflictDecision.reasonCode(),
                    duplicateConflictDecision.reasonMessage(),
                    payloadOf(
                            "source", source,
                            "allocation", allocationDecision.toPayload(),
                            "duplicateConflict", duplicateConflictDecision.toPayload()));
            return CandidateProcessResult.rejectedResult(true);
        }

        BigDecimal reservedMarginUsdt = resolveReservedMarginUsdt(preflight);
        if (reservedMarginUsdt == null || reservedMarginUsdt.compareTo(BigDecimal.ZERO) <= 0) {
            rejectRecommendation(session,
                    scanRun,
                    recommendation,
                    RESERVED_MARGIN_UNAVAILABLE,
                    "Preflight did not produce a valid reserved margin requirement.",
                    payloadOf(
                            "source", source,
                            "allocation", allocationDecision.toPayload(),
                            "preflight", preflight));
            return CandidateProcessResult.rejectedResult(true);
        }
        if (reservedMarginUsdt.compareTo(allocationDecision.allocatedSliceUsdt()) > 0
                || reservedMarginUsdt.compareTo(allocationDecision.spendableBudgetUsdt()) > 0) {
            rejectRecommendation(session,
                    scanRun,
                    recommendation,
                    ALLOCATED_SLICE_EXCEEDED,
                    "Reserved exposure required by preflight exceeds the allocated per-trade slice.",
                    payloadOf(
                            "source", source,
                            "allocation", allocationDecision.toPayload(),
                            "requiredReservedMarginUsdt", reservedMarginUsdt));
            return CandidateProcessResult.rejectedResult(true);
        }

        try {
            LiveTradeExecutionDTO execution = liveTradingExecutionService.executeAutoSession(
                    recommendation.getId(),
                    session.getId(),
                    allocationDecision.allocatedSliceUsdt(),
                    "system",
                    "auto-session-" + session.getId());
            if ("PREFLIGHT_REJECTED".equalsIgnoreCase(execution.getExecutionState())
                    || "FAILED".equalsIgnoreCase(execution.getExecutionState())) {
                if (countsAsExecutionFailure(execution)) {
                    session = lifecycleService.recordExecutionFailure(
                            session,
                            null,
                            firstNonBlank(execution.getErrorCode(), "EXECUTION_FAILED"),
                            firstNonBlank(
                                    execution.getErrorMessage(),
                                    "The shared live execution path failed before exchange-confirmed protection."),
                            payloadOf(
                                    "source", source,
                                    "allocation", allocationDecision.toPayload(),
                                    "execution", execution));
                    if (session != null && session.getStatus().isTerminal()) {
                        return CandidateProcessResult.rejectedResult(true);
                    }
                }
                rejectRecommendation(session,
                        scanRun,
                        recommendation,
                        firstNonBlank(execution.getErrorCode(), EVENT_INTAKE_REJECTED),
                        firstNonBlank(
                                execution.getErrorMessage(),
                                "The shared live execution path rejected this recommendation."),
                        payloadOf(
                                "source", source,
                                "allocation", allocationDecision.toPayload(),
                                "execution", execution));
                return CandidateProcessResult.rejectedResult(true);
            }

            acceptRecommendation(session,
                    scanRun,
                    recommendation,
                    execution,
                    payloadOf(
                            "source", source,
                            "allocation", allocationDecision.toPayload(),
                            "preflightReservedMarginUsdt", reservedMarginUsdt));
            return CandidateProcessResult.acceptedResult();
        } catch (Exception ex) {
            log.warn("Auto-session recommendation execution failed: sessionId={}, recommendationId={}, error={}",
                    session.getId(), recommendation.getId(), ex.getMessage());
            session = lifecycleService.recordExecutionFailure(
                    session,
                    null,
                    "EXECUTION_FAILED",
                    "Failed to execute the recommendation through the live execution service.",
                    payloadOf(
                            "source", source,
                            "allocation", allocationDecision.toPayload(),
                            "error", ex.getMessage()));
            if (session != null && session.getStatus().isTerminal()) {
                return CandidateProcessResult.rejectedResult(true);
            }
            rejectRecommendation(session,
                    scanRun,
                    recommendation,
                    "EXECUTION_FAILED",
                    firstNonBlank(
                            ex.getMessage(),
                            "Failed to execute the recommendation through the live execution service."),
                    payloadOf(
                            "source", source,
                            "allocation", allocationDecision.toPayload(),
                            "error", ex.getMessage()));
            return CandidateProcessResult.rejectedResult(true);
        }
    }

    private void acceptRecommendation(BudgetTargetSession session,
            ScanRun scanRun,
            Recommendation recommendation,
            LiveTradeExecutionDTO execution,
            Map<String, Object> payload) {
        lifecycleService.setVisibleFailure(session, null, null);
        auditDecision(session,
                scanRun,
                recommendation,
                execution != null ? execution.getId() : null,
                recommendation.getSymbol(),
                EVENT_INTAKE_ACCEPTED,
                "Submitted a recommendation through the shared live execution path.",
                payloadOf(
                        "execution", execution,
                        "payload", payload));
        lifecycleService.appendSessionEvent(session,
                "RECOMMENDATION_EXECUTED",
                "Submitted a recommendation through the shared live execution path.",
                null,
                payloadOf(
                        "recommendationId", recommendation.getId(),
                        "executionId", execution != null ? execution.getId() : null,
                        "symbol", recommendation.getSymbol(),
                        "executionState", execution != null ? execution.getExecutionState() : null));
    }

    private void rejectRecommendation(BudgetTargetSession session,
            ScanRun scanRun,
            Recommendation recommendation,
            String reasonCode,
            String reasonMessage,
            Map<String, Object> payload) {
        lifecycleService.setVisibleFailure(session, reasonCode, reasonMessage);
        auditDecision(session,
                scanRun,
                recommendation,
                extractExecutionId(payload),
                recommendation.getSymbol(),
                EVENT_INTAKE_REJECTED,
                reasonMessage,
                payloadOf(
                        "reasonCode", reasonCode,
                        "reasonMessage", reasonMessage,
                        "payload", payload));
        lifecycleService.appendSessionEvent(session,
                "RECOMMENDATION_SKIPPED",
                reasonMessage,
                reasonCode,
                payloadOf(
                        "recommendationId", recommendation.getId(),
                        "symbol", recommendation.getSymbol(),
                        "reasonCode", reasonCode,
                        "payload", payload));
    }

    private void requestSessionOwnedScan(BudgetTargetSession session,
            BudgetAllocationDecision allocationDecision,
            boolean auditAllocation) {
        if (auditAllocation) {
            auditBudgetDecision(session,
                    null,
                    null,
                    allocationDecision,
                    "Budget allocator approved a per-trade slice for a session-owned scan request.",
                    payloadOf("source", "SCAN_REQUEST"));
        }
        try {
            ScanOrchestrator.ScanStartResult startResult = scanOrchestrator.runAutoSession(
                    "auto-session-" + session.getId() + "-" + UUID.randomUUID(),
                    allocationDecision.allocatedSliceUsdt());
            lifecycleService.setPendingScanRun(session,
                    startResult.scanRunId(),
                    "Auto-execution requested a scan cycle using the allocated per-trade budget slice.");
        } catch (Exception ex) {
            lifecycleService.appendSessionEvent(session,
                    EVENT_SCAN_FAILED,
                    "Failed to request an auto-session scan cycle.",
                    EVENT_SCAN_FAILED,
                    payloadOf(
                            "allocatedSliceUsdt", allocationDecision.allocatedSliceUsdt(),
                            "error", ex.getMessage()));
        }
    }

    private void auditBudgetDecision(BudgetTargetSession session,
            ScanRun scanRun,
            Recommendation recommendation,
            BudgetAllocationDecision allocationDecision,
            String notes,
            Map<String, Object> extraPayload) {
        String eventType = allocationDecision.allowed() ? EVENT_BUDGET_ALLOCATED : EVENT_BUDGET_REJECTED;
        auditDecision(session,
                scanRun,
                recommendation,
                null,
                recommendation != null ? recommendation.getSymbol() : EVENT_SESSION_SYMBOL,
                eventType,
                notes,
                payloadOf(
                        "allocation", allocationDecision.toPayload(),
                        "payload", extraPayload));
    }

    private BigDecimal sumRealizedNetPnl(UUID sessionId) {
        BigDecimal sum = liveTradePnlLedgerRepository.sumNetPnlBySessionId(sessionId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private int countOpenedPositions(UUID sessionId) {
        return (int) liveTradeExecutionRepository.findBySession_IdOrderByCreatedAtDesc(sessionId).stream()
                .filter(this::hasOpenedPosition)
                .count();
    }

    private int countClosedPositions(UUID sessionId) {
        return (int) liveTradeExecutionRepository.findBySession_IdOrderByCreatedAtDesc(sessionId).stream()
                .filter(this::hasOpenedPosition)
                .filter(execution -> !execution.getExecutionStatus().isActive())
                .count();
    }

    private boolean hasOpenedPosition(LiveTradeExecution execution) {
        return execution.getActualFilledQty() != null
                && execution.getActualFilledQty().compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal sumUnrealizedNetPnl(List<LiveTradeExecution> activeExecutions) {
        return activeExecutions.stream()
                .map(this::extractUnrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal extractUnrealizedPnl(LiveTradeExecution execution) {
        Map<String, Object> exchangeResponse = readJson(execution.getExchangeResponseJson());
        Object position = exchangeResponse.get("position");
        if (!(position instanceof Map<?, ?> positionMap)) {
            return BigDecimal.ZERO;
        }
        Object value = positionMap.get("unRealizedProfit");
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException ignored) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal resolveReservedMarginUsdt(LiveTradingPreflightDTO preflight) {
        if (preflight == null || preflight.getExchangeValidation() == null) {
            return null;
        }
        BigDecimal notional = preflight.getExchangeValidation().getEntryNotionalUsdt();
        Integer leverage = preflight.getExchangeValidation().getLeverage();
        if (notional == null || leverage == null || leverage <= 0) {
            return null;
        }
        return notional.divide(BigDecimal.valueOf(leverage), 8, RoundingMode.HALF_UP);
    }

    private void clearRecoveredSyncFailure(BudgetTargetSession session) {
        if (session == null || !exchangeSyncSnapshotService.isSyncHealthGateCode(session.getLastErrorCode())) {
            return;
        }
        lifecycleService.setVisibleFailure(session, null, null);
    }

    private String firstBlockedCode(LiveTradingPreflightDTO preflight) {
        if (preflight == null || preflight.getBlockedReasons() == null || preflight.getBlockedReasons().isEmpty()) {
            return null;
        }
        return preflight.getBlockedReasons().getFirst().getCode();
    }

    private String firstBlockedMessage(LiveTradingPreflightDTO preflight) {
        if (preflight == null || preflight.getBlockedReasons() == null || preflight.getBlockedReasons().isEmpty()) {
            return null;
        }
        return preflight.getBlockedReasons().getFirst().getMessage();
    }

    private UUID extractExecutionId(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object executionValue = payload.get("execution");
        if (executionValue instanceof LiveTradeExecutionDTO execution) {
            return execution.getId();
        }
        if (executionValue instanceof Map<?, ?> executionMap) {
            Object idValue = executionMap.get("id");
            if (idValue instanceof UUID uuid) {
                return uuid;
            }
            if (idValue instanceof String text && !text.isBlank()) {
                try {
                    return UUID.fromString(text);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean countsAsExecutionFailure(LiveTradeExecutionDTO execution) {
        return execution != null && "FAILED".equalsIgnoreCase(execution.getExecutionState());
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            return Map.of("raw", json);
        }
    }

    private void auditDecision(BudgetTargetSession session,
            ScanRun scanRun,
            Recommendation recommendation,
            UUID executionId,
            String symbol,
            String eventType,
            String notes,
            Object afterState) {
        LiveTradeExecution execution = executionId != null
                ? liveTradeExecutionRepository.findById(executionId).orElse(null)
                : null;
        SessionSymbolDecisionAudit audit = new SessionSymbolDecisionAudit();
        audit.setSession(session);
        audit.setScanRun(scanRun);
        audit.setRecommendation(recommendation);
        audit.setExecution(execution);
        audit.setSymbol(symbol != null ? symbol : recommendation != null ? recommendation.getSymbol() : EVENT_SESSION_SYMBOL);
        audit.setEventType(eventType);
        audit.setEventTs(Instant.now());
        audit.setNotes(notes);
        audit.setTraceId(session.getTraceId());
        audit.setEventCategory(BudgetTargetAuditSupport.categoryForDecisionEvent());
        audit.setSeverity(BudgetTargetAuditSupport.severityForDecisionEvent(eventType));
        audit.setActor(BudgetTargetAuditSupport.normalizeActor(session.getStartedBy()));
        audit.setBeforeJson(writeJson(BudgetTargetAuditSupport.decisionSnapshot(
                session,
                scanRun,
                recommendation,
                execution,
                audit.getSymbol(),
                session.getStartedBy(),
                null,
                null)));
        audit.setAfterJson(writeJson(BudgetTargetAuditSupport.decisionSnapshot(
                session,
                scanRun,
                recommendation,
                execution,
                audit.getSymbol(),
                session.getStartedBy(),
                eventType,
                afterState)));
        SessionSymbolDecisionAudit saved = sessionSymbolDecisionAuditRepository.save(audit);
        sessionStreamPublisher.publish(saved);
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Object> payloadOf(Object... entries) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (entries == null) {
            return payload;
        }
        for (int index = 0; index + 1 < entries.length; index += 2) {
            Object key = entries[index];
            if (key instanceof String stringKey) {
                payload.put(stringKey, entries[index + 1]);
            }
        }
        return payload;
    }

    private record CandidateProcessResult(boolean accepted, boolean awaitingScan, boolean budgetAuditWritten) {

        private static CandidateProcessResult acceptedResult() {
            return new CandidateProcessResult(true, false, true);
        }

        private static CandidateProcessResult rejectedResult(boolean budgetAuditWritten) {
            return new CandidateProcessResult(false, false, budgetAuditWritten);
        }

        private static CandidateProcessResult awaitingScanResult() {
            return new CandidateProcessResult(false, true, false);
        }
    }
}
