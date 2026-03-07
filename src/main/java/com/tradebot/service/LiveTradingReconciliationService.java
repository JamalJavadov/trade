package com.tradebot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
import com.tradebot.dto.BinanceFuturesOrderResponse;
import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionEvent;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.repository.LiveTradeExecutionEventRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LiveTradingReconciliationService {

    private static final int DEFAULT_RECONCILIATION_INTERVAL_SEC = 30;
    private static final Set<LiveTradeExecutionState> RECONCILE_STATES = EnumSet.of(
            LiveTradeExecutionState.ENTRY_SUBMITTED,
            LiveTradeExecutionState.PROTECTION_SUBMITTED,
            LiveTradeExecutionState.OPEN,
            LiveTradeExecutionState.PENDING_RECONCILE,
            LiveTradeExecutionState.EMERGENCY_CLOSE_SUBMITTED);

    private final LiveTradeExecutionRepository liveTradeExecutionRepository;
    private final LiveTradeExecutionEventRepository liveTradeExecutionEventRepository;
    private final BinanceClient binanceClient;
    private final LiveTradingMapper liveTradingMapper;
    private final ObjectMapper objectMapper;
    private volatile Instant nextScheduledRunAt = Instant.EPOCH;

    public LiveTradeExecutionDTO reconcileExecution(UUID executionId, String actor, String traceId, boolean scheduled) {
        LiveTradeExecution execution = liveTradeExecutionRepository.findById(executionId)
                .orElseThrow(() -> new NoSuchElementException("Live execution not found: " + executionId));

        if (execution.isDryRun()) {
            appendEvent(execution, "RECONCILE_SKIPPED", execution.getExecutionState().name(),
                    "Dry-run executions do not require Binance reconciliation.",
                    null,
                    payloadOf("scheduled", scheduled));
            return liveTradingMapper.toDetail(execution,
                    liveTradeExecutionEventRepository.findByExecution_IdOrderByCreatedAtAsc(executionId));
        }
        if (!RECONCILE_STATES.contains(execution.getExecutionState()) || !hasLookupableOrders(execution)) {
            appendEvent(execution, "RECONCILE_SKIPPED", execution.getExecutionState().name(),
                    "Execution state is not eligible for Binance reconciliation.",
                    null,
                    payloadOf("scheduled", scheduled));
            return liveTradingMapper.toDetail(execution,
                    liveTradeExecutionEventRepository.findByExecution_IdOrderByCreatedAtAsc(executionId));
        }

        Map<String, Object> statuses = new LinkedHashMap<>();
        try {
            BinanceFuturesOrderResponse entry = lookupOrder(execution.getSymbol(),
                    execution.getEntryClientOrderId(),
                    execution.getEntryOrderId());
            BinanceFuturesOrderResponse stopLoss = lookupOrder(execution.getSymbol(),
                    execution.getSlClientOrderId(),
                    execution.getSlOrderId());
            BinanceFuturesOrderResponse takeProfit = lookupOrder(execution.getSymbol(),
                    execution.getTpClientOrderId(),
                    execution.getTpOrderId());
            BinanceFuturesOrderResponse emergencyClose = lookupOrder(execution.getSymbol(),
                    execution.getEmergencyCloseClientOrderId(),
                    execution.getEmergencyCloseOrderId());

            statuses.put("entry", summary(entry));
            statuses.put("stopLoss", summary(stopLoss));
            statuses.put("takeProfit", summary(takeProfit));
            statuses.put("emergencyClose", summary(emergencyClose));

            LiveTradeExecutionState resolvedState = resolveState(entry, stopLoss, takeProfit, emergencyClose);
            execution.setExecutionState(resolvedState);
            execution.setExchangeResponseJson(writeJson(statuses));
            execution.setLastReconciledAt(Instant.now());
            execution.setUpdatedAt(Instant.now());
            execution.setReconcileCount(execution.getReconcileCount() + 1);
            execution.setErrorCode(null);
            execution.setErrorMessage(null);
            if (!resolvedState.isActive() && execution.getCompletedAt() == null) {
                execution.setCompletedAt(Instant.now());
            }
            liveTradeExecutionRepository.save(execution);
            appendEvent(execution, scheduled ? "SCHEDULED_RECONCILE" : "RECONCILE",
                    execution.getExecutionState().name(),
                    "Reconciled live execution against Binance order status.",
                    null,
                    payloadOf(
                            "scheduled", scheduled,
                            "actor", actor == null || actor.isBlank() ? "system" : actor,
                            "traceId", traceId,
                            "statuses", statuses));
            return liveTradingMapper.toDetail(execution,
                    liveTradeExecutionEventRepository.findByExecution_IdOrderByCreatedAtAsc(execution.getId()));
        } catch (WebClientRequestException ex) {
            execution.setExecutionState(LiveTradeExecutionState.PENDING_RECONCILE);
            execution.setErrorCode("UPSTREAM_TIMEOUT");
            execution.setErrorMessage("Reconciliation timed out. Retry later.");
            execution.setLastReconciledAt(Instant.now());
            execution.setUpdatedAt(Instant.now());
            execution.setReconcileCount(execution.getReconcileCount() + 1);
            liveTradeExecutionRepository.save(execution);
            appendEvent(execution, "RECONCILE_PENDING", execution.getExecutionState().name(),
                    "Binance reconciliation timed out.",
                    execution.getErrorCode(),
                    payloadOf("message", ex.getMessage(), "traceId", traceId));
            return liveTradingMapper.toDetail(execution,
                    liveTradeExecutionEventRepository.findByExecution_IdOrderByCreatedAtAsc(execution.getId()));
        } catch (WebClientResponseException ex) {
            execution.setExecutionState(LiveTradeExecutionState.PENDING_RECONCILE);
            execution.setErrorCode("BINANCE_RECONCILE_REJECTED");
            execution.setErrorMessage(ex.getMessage());
            execution.setLastReconciledAt(Instant.now());
            execution.setUpdatedAt(Instant.now());
            execution.setReconcileCount(execution.getReconcileCount() + 1);
            liveTradeExecutionRepository.save(execution);
            appendEvent(execution, "RECONCILE_PENDING", execution.getExecutionState().name(),
                    "Binance rejected reconciliation lookup.",
                    execution.getErrorCode(),
                    payloadOf("status", ex.getRawStatusCode(), "message", ex.getMessage(), "traceId", traceId));
            return liveTradingMapper.toDetail(execution,
                    liveTradeExecutionEventRepository.findByExecution_IdOrderByCreatedAtAsc(execution.getId()));
        }
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
                reconcileExecution(execution.getId(), "system-scheduler", execution.getTraceId(), true);
            } catch (Exception ex) {
                log.warn("Scheduled reconciliation failed for execution {}: {}", execution.getId(), ex.getMessage());
            }
        }
    }

    private LiveTradeExecutionState resolveState(BinanceFuturesOrderResponse entry,
            BinanceFuturesOrderResponse stopLoss,
            BinanceFuturesOrderResponse takeProfit,
            BinanceFuturesOrderResponse emergencyClose) {
        if (isFilled(emergencyClose) || isFilled(stopLoss) || isFilled(takeProfit)) {
            return LiveTradeExecutionState.RECONCILED;
        }
        if (isFilled(entry) && (isWorking(stopLoss) || isWorking(takeProfit))) {
            return LiveTradeExecutionState.OPEN;
        }
        if (entry != null && isFilled(entry)) {
            return LiveTradeExecutionState.PENDING_RECONCILE;
        }
        return LiveTradeExecutionState.PENDING_RECONCILE;
    }

    private BinanceFuturesOrderResponse lookupOrder(String symbol, String clientOrderId, Long orderId) {
        if ((clientOrderId == null || clientOrderId.isBlank()) && orderId == null) {
            return null;
        }
        try {
            return binanceClient.getOrder(symbol, clientOrderId, orderId);
        } catch (WebClientResponseException ex) {
            if (ex.getRawStatusCode() == 400 || ex.getRawStatusCode() == 404) {
                return null;
            }
            throw ex;
        }
    }

    private boolean hasLookupableOrders(LiveTradeExecution execution) {
        return hasLookupReference(execution.getEntryClientOrderId(), execution.getEntryOrderId())
                || hasLookupReference(execution.getSlClientOrderId(), execution.getSlOrderId())
                || hasLookupReference(execution.getTpClientOrderId(), execution.getTpOrderId())
                || hasLookupReference(execution.getEmergencyCloseClientOrderId(), execution.getEmergencyCloseOrderId());
    }

    private boolean hasLookupReference(String clientOrderId, Long orderId) {
        return (clientOrderId != null && !clientOrderId.isBlank()) || orderId != null;
    }

    private boolean isFilled(BinanceFuturesOrderResponse response) {
        return response != null && "FILLED".equalsIgnoreCase(response.getStatus());
    }

    private boolean isWorking(BinanceFuturesOrderResponse response) {
        return response != null && ("NEW".equalsIgnoreCase(response.getStatus())
                || "PARTIALLY_FILLED".equalsIgnoreCase(response.getStatus()));
    }

    private Map<String, Object> summary(BinanceFuturesOrderResponse response) {
        if (response == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderId", response.getOrderId());
        summary.put("clientOrderId", response.getClientOrderId());
        summary.put("status", response.getStatus());
        summary.put("executedQty", response.getExecutedQty());
        summary.put("avgPrice", response.getAvgPrice());
        summary.put("stopPrice", response.getStopPrice());
        summary.put("updateTime", response.getUpdateTime());
        return summary;
    }

    private void appendEvent(LiveTradeExecution execution,
            String eventType,
            String eventStatus,
            String message,
            String errorCode,
            Object payload) {
        LiveTradeExecutionEvent event = new LiveTradeExecutionEvent();
        event.setExecution(execution);
        event.setEventType(eventType);
        event.setEventStatus(eventStatus);
        event.setMessage(message);
        event.setErrorCode(errorCode);
        event.setPayloadJson(writeJson(payload));
        event.setCreatedAt(Instant.now());
        liveTradeExecutionEventRepository.save(event);
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
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("Payload entries must be key/value pairs.");
        }
        for (int index = 0; index < entries.length; index += 2) {
            Object key = entries[index];
            if (!(key instanceof String stringKey)) {
                throw new IllegalArgumentException("Payload keys must be strings.");
            }
            payload.put(stringKey, entries[index + 1]);
        }
        return payload;
    }
}
