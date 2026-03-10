package com.tradebot.service;

import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.BudgetTargetSessionCompletionReason;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.repository.BudgetTargetSessionRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BudgetTargetAutoExecutionRecoveryService {

    private static final List<LiveTradeExecutionState> ACTIVE_EXECUTION_STATES = Arrays.stream(LiveTradeExecutionState.values())
            .filter(LiveTradeExecutionState::isActive)
            .toList();

    private final BudgetTargetSessionRepository budgetTargetSessionRepository;
    private final LiveTradeExecutionRepository liveTradeExecutionRepository;
    private final LiveTradePersistenceService liveTradePersistenceService;
    private final BudgetTargetAutoExecutionLifecycleService lifecycleService;
    private final BudgetTargetAutoExecutionCoordinator coordinator;
    private final BudgetTargetAutoExecutionCloseAllService closeAllService;
    private final LiveTradingReconciliationService liveTradingReconciliationService;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnApplicationReady() {
        recoverPersistedSession();
    }

    public void recoverPersistedSession() {
        BudgetTargetSession session = lifecycleService.findActiveSession().orElse(null);
        if (session == null) {
            lifecycleService.syncRuntimeWithControlCenter();
            return;
        }

        lifecycleService.appendSessionEvent(
                session,
                "SESSION_RECOVERY_STARTED",
                "Recovered a persisted budget-target auto-execution session after restart.",
                null,
                lifecyclePayload(session));
        try {
            List<LiveTradeExecution> activeExecutions = liveTradeExecutionRepository
                    .findBySession_IdAndExecutionStatusInOrderByCloseAllPriority(session.getId(), ACTIVE_EXECUTION_STATES);
            for (LiveTradeExecution execution : activeExecutions) {
                liveTradingReconciliationService.reconcileExecution(
                        execution.getId(),
                        "system-recovery",
                        execution.getTraceId(),
                        false);
            }

            liveTradePersistenceService.refreshSessionRollup(session, ACTIVE_EXECUTION_STATES);
            session = budgetTargetSessionRepository.findById(session.getId()).orElse(session);
            if (session.getStatus() == com.tradebot.entity.BudgetTargetSessionStatus.STOPPING) {
                session = closeAllService.closeAllIfNeeded(session, "system-recovery", session.getTraceId());
            } else {
                coordinator.reconcileActiveSession();
                session = budgetTargetSessionRepository.findById(session.getId()).orElse(session);
            }
            lifecycleService.appendSessionEvent(
                    session,
                    "SESSION_RECOVERY_COMPLETED",
                    "Startup recovery finished reconciling the persisted session.",
                    null,
                    lifecyclePayload(session));
        } catch (Exception ex) {
            log.error("Budget-target auto-execution recovery failed for session {}: {}", session.getId(), ex.getMessage(), ex);
            session = budgetTargetSessionRepository.findById(session.getId()).orElse(session);
            lifecycleService.appendSessionEvent(
                    session,
                    "SESSION_RECOVERY_FAILED",
                    "Startup recovery failed and the session moved to a fatal state.",
                    BudgetTargetSessionCompletionReason.FATAL_SYNC_ERROR.name(),
                    lifecyclePayload(session));
            lifecycleService.failSession(
                    session,
                    BudgetTargetSessionCompletionReason.FATAL_SYNC_ERROR,
                    BudgetTargetSessionCompletionReason.FATAL_SYNC_ERROR.name(),
                    "Startup recovery failed: " + ex.getMessage());
        }
    }

    private Object lifecyclePayload(BudgetTargetSession session) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("sessionId", session.getId());
        payload.put("status", session.getStatus() != null ? session.getStatus().name() : null);
        payload.put("pendingScanRunId", session.getPendingScanRunId());
        payload.put("executionFailureCount", session.getExecutionFailureCount());
        return payload;
    }
}
