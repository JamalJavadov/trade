package com.tradebot.service;

import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.repository.LiveTradeExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LiveTradingReconciliationService {

    private static final int DEFAULT_RECONCILIATION_INTERVAL_SEC = 30;
    private static final Set<LiveTradeExecutionState> RECONCILE_STATES = EnumSet.of(
            LiveTradeExecutionState.ENTRY_SUBMITTED,
            LiveTradeExecutionState.ENTRY_FILLED,
            LiveTradeExecutionState.PROTECTION_SUBMITTING,
            LiveTradeExecutionState.PROTECTION_ACTIVE,
            LiveTradeExecutionState.ACTIVE,
            LiveTradeExecutionState.CLOSING,
            LiveTradeExecutionState.RECONCILING);

    private final LiveTradeExecutionRepository liveTradeExecutionRepository;
    private final OrderStateSyncService orderStateSyncService;
    private volatile Instant nextScheduledRunAt = Instant.EPOCH;

    public LiveTradeExecutionDTO reconcileExecution(UUID executionId, String actor, String traceId, boolean scheduled) {
        return orderStateSyncService.reconcileExecution(executionId, actor, traceId, scheduled);
    }

    @Scheduled(fixedDelay = 5_000L, initialDelay = 20_000L)
    public void scheduledReconcile() {
        Instant now = Instant.now();
        if (now.isBefore(nextScheduledRunAt)) {
            return;
        }
        nextScheduledRunAt = now.plusSeconds(DEFAULT_RECONCILIATION_INTERVAL_SEC);

        List<LiveTradeExecution> pending = liveTradeExecutionRepository
                .findByExecutionStateInAndUpdatedAtBeforeOrderByUpdatedAtAsc(RECONCILE_STATES,
                        now.minusSeconds(DEFAULT_RECONCILIATION_INTERVAL_SEC));
        for (LiveTradeExecution execution : pending) {
            try {
                orderStateSyncService.reconcileExecution(execution.getId(), "system-scheduler", execution.getTraceId(), true);
            } catch (Exception ex) {
                log.warn("Scheduled reconciliation failed for execution {}: {}", execution.getId(), ex.getMessage());
            }
        }
    }
}
