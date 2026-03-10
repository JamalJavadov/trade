package com.tradebot.service;

import com.tradebot.entity.BudgetTargetAuditEventCategory;
import com.tradebot.entity.BudgetTargetAuditSeverity;
import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.Recommendation;
import com.tradebot.entity.ScanRun;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class BudgetTargetAuditSupport {

    private BudgetTargetAuditSupport() {
    }

    public static String normalizeActor(String actor) {
        if (actor == null || actor.isBlank()) {
            return "system";
        }
        return actor.trim();
    }

    public static BudgetTargetAuditEventCategory categoryForSessionEvent() {
        return BudgetTargetAuditEventCategory.SESSION;
    }

    public static BudgetTargetAuditEventCategory categoryForDecisionEvent() {
        return BudgetTargetAuditEventCategory.TRADE;
    }

    public static BudgetTargetAuditEventCategory categoryForExecutionEvent(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return BudgetTargetAuditEventCategory.TRADE;
        }
        return switch (eventType) {
            case "ENTRY_SUBMITTING",
                    "ENTRY_SUBMITTED",
                    "PROTECTION_SUBMITTING",
                    "PROTECTION_ACTIVE",
                    "ACTIVE",
                    "RECONCILE",
                    "SCHEDULED_RECONCILE",
                    "RECONCILING",
                    "CLOSING",
                    "SAFE_CLOSE_SUBMITTED",
                    "SAFE_CLOSE_ALREADY_SUBMITTED",
                    "SAFE_CLOSE_SKIPPED",
                    "SAFE_CLOSE_TIMEOUT",
                    "SAFE_CLOSE_FAILED",
                    "FAILED" -> BudgetTargetAuditEventCategory.EXCHANGE;
            default -> BudgetTargetAuditEventCategory.TRADE;
        };
    }

    public static BudgetTargetAuditSeverity severityForSessionEvent(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return BudgetTargetAuditSeverity.INFO;
        }
        return switch (eventType) {
            case "SESSION_FAILED", "SESSION_STOPPED_WITH_ERROR", "SESSION_RECOVERY_FAILED",
                    "EXECUTION_PNL_UNRESOLVED" -> BudgetTargetAuditSeverity.ERROR;
            case "SESSION_BLOCKED", "ACTIVE_LIMIT_REACHED", "BANKROLL_EXHAUSTED", "SCAN_FAILED",
                    "SESSION_EXECUTION_FAILURE", "RECOMMENDATION_SKIPPED", "CLOSE_ALL_ATTEMPT" -> BudgetTargetAuditSeverity.WARN;
            default -> BudgetTargetAuditSeverity.INFO;
        };
    }

    public static BudgetTargetAuditSeverity severityForDecisionEvent(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return BudgetTargetAuditSeverity.INFO;
        }
        return switch (eventType) {
            case "INTAKE_REJECTED", "BUDGET_REJECTED" -> BudgetTargetAuditSeverity.WARN;
            default -> BudgetTargetAuditSeverity.INFO;
        };
    }

    public static BudgetTargetAuditSeverity severityForExecutionEvent(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return BudgetTargetAuditSeverity.INFO;
        }
        return switch (eventType) {
            case "FAILED", "SAFE_CLOSE_TIMEOUT", "SAFE_CLOSE_FAILED" -> BudgetTargetAuditSeverity.ERROR;
            case "PREFLIGHT_REJECTED", "RECONCILE_SKIPPED", "SAFE_CLOSE_SKIPPED", "SAFE_CLOSE_ALREADY_SUBMITTED" ->
                    BudgetTargetAuditSeverity.WARN;
            default -> BudgetTargetAuditSeverity.INFO;
        };
    }

    public static Map<String, Object> sessionSnapshot(BudgetTargetSession session) {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        if (session == null) {
            return snapshot;
        }
        snapshot.put("sessionId", session.getId());
        snapshot.put("status", session.getStatus() != null ? session.getStatus().name() : null);
        snapshot.put("completionReason", session.getCompletionReason() != null ? session.getCompletionReason().name() : null);
        snapshot.put("stopReason", session.getStopReason());
        snapshot.put("budgetAmountUsdt", session.getBudgetAmountUsdt());
        snapshot.put("targetProfitUsdt", session.getTargetProfitUsdt());
        snapshot.put("realizedNetPnlUsdt", session.getRealizedNetPnlUsdt());
        snapshot.put("unrealizedNetPnlUsdt", session.getUnrealizedNetPnlUsdt());
        snapshot.put("maxConcurrentPositions", session.getMaxConcurrentPositions());
        snapshot.put("activePositionsCount", session.getActivePositionsCount());
        snapshot.put("openedPositionsTotal", session.getOpenedPositionsTotal());
        snapshot.put("closedPositionsTotal", session.getClosedPositionsTotal());
        snapshot.put("pendingScanRunId", session.getPendingScanRunId());
        snapshot.put("stopRequested", session.isStopRequested());
        snapshot.put("stopRequestedAt", session.getStopRequestedAt());
        snapshot.put("startedBy", session.getStartedBy());
        snapshot.put("stoppedBy", session.getStoppedBy());
        snapshot.put("traceId", session.getTraceId());
        snapshot.put("lastErrorCode", session.getLastErrorCode());
        snapshot.put("lastErrorMessage", session.getLastErrorMessage());
        snapshot.put("executionFailureCount", session.getExecutionFailureCount());
        snapshot.put("startedAt", session.getStartedAt());
        snapshot.put("endedAt", session.getEndedAt());
        snapshot.put("createdAt", session.getCreatedAt());
        snapshot.put("updatedAt", session.getUpdatedAt());
        return snapshot;
    }

    public static Map<String, Object> sessionEventState(BudgetTargetSession session,
            String actor,
            String reasonCode,
            Object payload) {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>(sessionSnapshot(session));
        snapshot.put("actor", normalizeActor(actor));
        snapshot.put("reasonCode", reasonCode);
        snapshot.put("payload", payload);
        return snapshot;
    }

    public static Map<String, Object> executionSnapshot(LiveTradeExecution execution) {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        if (execution == null) {
            return snapshot;
        }
        snapshot.put("executionId", execution.getId());
        snapshot.put("sessionId", execution.getSession() != null ? execution.getSession().getId() : null);
        snapshot.put("recommendationId", execution.getRecommendation() != null ? execution.getRecommendation().getId() : null);
        snapshot.put("symbol", execution.getSymbol());
        snapshot.put("side", execution.getSide());
        snapshot.put("triggerMode", execution.getTriggerMode() != null ? execution.getTriggerMode().name() : null);
        snapshot.put("operatorId", execution.getOperatorId());
        snapshot.put("traceId", execution.getTraceId());
        snapshot.put("executionStatus", execution.getExecutionState() != null ? execution.getExecutionState().name() : null);
        snapshot.put("errorCode", execution.getErrorCode());
        snapshot.put("errorMessage", execution.getErrorMessage());
        snapshot.put("requiresIntervention", execution.isRequiresIntervention());
        snapshot.put("reservedMarginUsdt", execution.getReservedMarginUsdt());
        snapshot.put("requestedBudgetSliceUsdt", execution.getRequestedBudgetSliceUsdt());
        snapshot.put("requestedQty", execution.getRequestedQty());
        snapshot.put("actualFilledQty", execution.getActualFilledQty());
        snapshot.put("realizedGrossPnlUsdt", execution.getRealizedGrossPnlUsdt());
        snapshot.put("realizedFeesUsdt", execution.getRealizedFeesUsdt());
        snapshot.put("realizedNetPnlUsdt", execution.getRealizedNetPnlUsdt());
        snapshot.put("closeReason", execution.getCloseReason());
        snapshot.put("positionSlot", execution.getPositionSlot());
        snapshot.put("submittedAt", execution.getSubmittedAt());
        snapshot.put("completedAt", execution.getCompletedAt());
        snapshot.put("lastReconciledAt", execution.getLastReconciledAt());
        snapshot.put("createdAt", execution.getCreatedAt());
        snapshot.put("updatedAt", execution.getUpdatedAt());
        return snapshot;
    }

    public static Map<String, Object> executionEventState(LiveTradeExecution execution,
            String actor,
            String reasonCode,
            Object payload) {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>(executionSnapshot(execution));
        snapshot.put("actor", normalizeActor(actor));
        snapshot.put("reasonCode", reasonCode);
        snapshot.put("payload", payload);
        return snapshot;
    }

    public static Map<String, Object> decisionSnapshot(BudgetTargetSession session,
            ScanRun scanRun,
            Recommendation recommendation,
            LiveTradeExecution execution,
            String symbol,
            String actor,
            String reasonCode,
            Object payload) {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("session", sessionSnapshot(session));
        snapshot.put("scanRun", scanRunSnapshot(scanRun));
        snapshot.put("recommendation", recommendationSnapshot(recommendation));
        snapshot.put("execution", executionSnapshot(execution));
        snapshot.put("symbol", symbol);
        snapshot.put("actor", normalizeActor(actor));
        snapshot.put("reasonCode", reasonCode);
        snapshot.put("payload", payload);
        return snapshot;
    }

    public static Map<String, Object> recommendationSnapshot(Recommendation recommendation) {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        if (recommendation == null) {
            return snapshot;
        }
        snapshot.put("recommendationId", recommendation.getId());
        snapshot.put("scanRunId", recommendation.getScanRun() != null ? recommendation.getScanRun().getId() : null);
        snapshot.put("symbol", recommendation.getSymbol());
        snapshot.put("side", recommendation.getSide());
        snapshot.put("status", recommendation.getStatus());
        snapshot.put("createdAt", recommendation.getCreatedAt());
        snapshot.put("confidenceScore", recommendation.getConfidenceScore());
        return snapshot;
    }

    public static Map<String, Object> scanRunSnapshot(ScanRun scanRun) {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        if (scanRun == null) {
            return snapshot;
        }
        snapshot.put("scanRunId", scanRun.getId());
        snapshot.put("status", scanRun.getStatus());
        snapshot.put("triggerType", scanRun.getTriggerType());
        snapshot.put("startedAt", scanRun.getStartedAt());
        snapshot.put("finishedAt", scanRun.getFinishedAt());
        snapshot.put("errorCode", scanRun.getErrorCode());
        snapshot.put("notes", scanRun.getNotes());
        return snapshot;
    }

    public static UUID executionId(LiveTradeExecution execution) {
        return execution != null ? execution.getId() : null;
    }
}
