package com.tradebot.service;

import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.BudgetTargetSessionCompletionReason;
import com.tradebot.entity.BudgetTargetSessionStatus;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.repository.BudgetTargetSessionRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class BudgetTargetAutoExecutionCloseAllService {

    private static final List<LiveTradeExecutionState> ACTIVE_EXECUTION_STATES = Arrays.stream(LiveTradeExecutionState.values())
            .filter(LiveTradeExecutionState::isActive)
            .toList();

    private final BudgetTargetSessionRepository budgetTargetSessionRepository;
    private final LiveTradeExecutionRepository liveTradeExecutionRepository;
    private final LiveTradePersistenceService liveTradePersistenceService;
    private final BudgetTargetAutoExecutionLifecycleService lifecycleService;
    private final LiveTradingExecutionService liveTradingExecutionService;

    public BudgetTargetSession closeAllIfNeeded(BudgetTargetSession session, String actor, String traceId) {
        if (session == null || session.getStatus() != BudgetTargetSessionStatus.STOPPING) {
            return session;
        }

        List<LiveTradeExecution> activeExecutions = liveTradeExecutionRepository
                .findBySession_IdAndExecutionStatusInOrderByCloseAllPriority(session.getId(), ACTIVE_EXECUTION_STATES);
        if (activeExecutions.isEmpty()) {
            return lifecycleService.completeSession(
                    session,
                    session.getCompletionReason(),
                    "Session reached a terminal state and all remaining active trades are exchange-confirmed flat.");
        }

        for (LiveTradeExecution execution : activeExecutions) {
            SafeCloseAttemptResult result = liveTradingExecutionService.requestSafeClose(
                    execution.getId(),
                    actor,
                    traceId,
                    session.getStopReason() != null ? session.getStopReason() : "SESSION_STOPPING");
            session = budgetTargetSessionRepository.findById(session.getId()).orElse(session);
            if (session.getStatus().isTerminal()) {
                return session;
            }

            Map<String, Object> payload = closeAttemptPayload(result);
            lifecycleService.appendSessionEvent(
                    session,
                    execution,
                    "CLOSE_ALL_ATTEMPT",
                    closeAttemptMessage(result),
                    result.errorCode() != null ? result.errorCode() : result.status().name(),
                    payload);

            if (result.countedAsFailure() && !isResolved(result)) {
                session = lifecycleService.recordExecutionFailure(
                        session,
                        execution,
                        result.errorCode() != null ? result.errorCode() : result.status().name(),
                        result.errorMessage() != null ? result.errorMessage() : closeAttemptMessage(result),
                        payload);
                if (session.getStatus().isTerminal()) {
                    return session;
                }
            }
        }

        liveTradePersistenceService.refreshSessionRollup(session, ACTIVE_EXECUTION_STATES);
        session = budgetTargetSessionRepository.findById(session.getId()).orElse(session);
        List<LiveTradeExecution> remaining = liveTradeExecutionRepository
                .findBySession_IdAndExecutionStatusInOrderByCloseAllPriority(session.getId(), ACTIVE_EXECUTION_STATES);
        if (remaining.isEmpty()) {
            return lifecycleService.completeSession(
                    session,
                    session.getCompletionReason(),
                    "Session reached a terminal state and all remaining active trades are exchange-confirmed flat.");
        }
        return session;
    }

    private boolean isResolved(SafeCloseAttemptResult result) {
        if (result == null || result.execution() == null || result.execution().getExecutionState() == null) {
            return false;
        }
        return "CLOSED".equalsIgnoreCase(result.execution().getExecutionState())
                || "FAILED".equalsIgnoreCase(result.execution().getExecutionState())
                || "PREFLIGHT_REJECTED".equalsIgnoreCase(result.execution().getExecutionState());
    }

    private String closeAttemptMessage(SafeCloseAttemptResult result) {
        if (result == null) {
            return "Close-all attempt returned no result.";
        }
        return switch (result.status()) {
            case SUBMITTED -> "Submitted a close-all reduce-only order and reconciled the execution.";
            case ALREADY_SUBMITTED -> "A close-all reduce-only order was already in flight; reused reconciliation instead of resubmitting.";
            case NO_POSITION -> "Close-all found no open Binance position and reconciled the execution.";
            case TIMED_OUT -> "Close-all submission timed out and requires further reconciliation.";
            case FAILED -> "Close-all failed before Binance confirmation.";
            case RESOLVED_FLAT -> "Close-all resolved the execution as flat from exchange truth.";
        };
    }

    private Map<String, Object> closeAttemptPayload(SafeCloseAttemptResult result) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (result == null) {
            return payload;
        }
        payload.put("status", result.status().name());
        payload.put("executionId", result.executionId());
        payload.put("errorCode", result.errorCode());
        payload.put("errorMessage", result.errorMessage());
        payload.put("details", result.details());
        payload.put("execution", result.execution());
        return payload;
    }
}
