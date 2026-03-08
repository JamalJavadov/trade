package com.tradebot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
import com.tradebot.dto.BinanceFuturesAlgoOrderResponse;
import com.tradebot.dto.BinanceFuturesOrderResponse;
import com.tradebot.dto.BinanceFuturesPositionRiskResponse;
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

import java.math.BigDecimal;
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
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<LiveTradeExecutionState> RECONCILE_STATES = EnumSet.of(
            LiveTradeExecutionState.ENTRY_SUBMITTED,
            LiveTradeExecutionState.ENTRY_PARTIALLY_FILLED,
            LiveTradeExecutionState.ENTRY_FILLED,
            LiveTradeExecutionState.PROTECTION_SUBMITTING,
            LiveTradeExecutionState.PROTECTION_SUBMITTED,
            LiveTradeExecutionState.PROTECTION_ACTIVE,
            LiveTradeExecutionState.OPEN,
            LiveTradeExecutionState.RECONCILING,
            LiveTradeExecutionState.PENDING_RECONCILE,
            LiveTradeExecutionState.PROTECTION_FAILED,
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

        Map<String, Object> exchangeResponses = readJson(execution.getExchangeResponseJson());
        try {
            BinanceFuturesOrderResponse entry = lookupOrder(execution.getSymbol(),
                    execution.getEntryClientOrderId(),
                    execution.getEntryOrderId());
            BinanceFuturesOrderResponse emergencyClose = lookupOrder(execution.getSymbol(),
                    execution.getEmergencyCloseClientOrderId(),
                    execution.getEmergencyCloseOrderId());

            List<BinanceFuturesAlgoOrderResponse> openAlgoOrders = lookupOpenAlgoOrders(execution.getSymbol());
            BinanceFuturesAlgoOrderResponse stopLoss = lookupAlgoOrder(execution.getSlClientOrderId(),
                    execution.getSlOrderId(),
                    openAlgoOrders);
            BinanceFuturesAlgoOrderResponse takeProfit = lookupAlgoOrder(execution.getTpClientOrderId(),
                    execution.getTpOrderId(),
                    openAlgoOrders);
            BinanceFuturesPositionRiskResponse position = lookupPosition(execution.getSymbol());

            exchangeResponses.put("entry", mergeSection(exchangeResponses.get("entry"), orderSummary(entry)));
            exchangeResponses.put("stopLoss", mergeSection(exchangeResponses.get("stopLoss"), algoSummary(stopLoss)));
            exchangeResponses.put("takeProfit", mergeSection(exchangeResponses.get("takeProfit"), algoSummary(takeProfit)));
            exchangeResponses.put("emergencyClose",
                    mergeSection(exchangeResponses.get("emergencyClose"), orderSummary(emergencyClose)));
            exchangeResponses.put("position", positionSummary(position));

            ReconciledOutcome outcome = resolveState(execution, entry, stopLoss, takeProfit, openAlgoOrders,
                    emergencyClose, position);
            exchangeResponses.put("reconciliation", outcome.summary());

            execution.setExecutionState(outcome.state());
            if (outcome.errorCode() != null) {
                execution.setErrorCode(outcome.errorCode());
            }
            if (outcome.errorMessage() != null) {
                execution.setErrorMessage(outcome.errorMessage());
            }
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            execution.setLastReconciledAt(Instant.now());
            execution.setUpdatedAt(Instant.now());
            execution.setReconcileCount(execution.getReconcileCount() + 1);
            if (!outcome.state().isActive() && execution.getCompletedAt() == null) {
                execution.setCompletedAt(Instant.now());
            }
            liveTradeExecutionRepository.save(execution);
            appendEvent(execution, scheduled ? "SCHEDULED_RECONCILE" : "RECONCILE",
                    execution.getExecutionState().name(),
                    "Reconciled live execution against Binance order and position truth.",
                    outcome.errorCode(),
                    payloadOf(
                            "scheduled", scheduled,
                            "actor", actor == null || actor.isBlank() ? "system" : actor,
                            "traceId", traceId,
                            "statuses", outcome.summary()));
            return liveTradingMapper.toDetail(execution,
                    liveTradeExecutionEventRepository.findByExecution_IdOrderByCreatedAtAsc(execution.getId()));
        } catch (WebClientRequestException ex) {
            execution.setExecutionState(LiveTradeExecutionState.RECONCILING);
            execution.setErrorCode(LiveTradingBlockerCodes.UPSTREAM_TIMEOUT);
            execution.setErrorMessage("Reconciliation timed out. Retry later.");
            execution.setLastReconciledAt(Instant.now());
            execution.setUpdatedAt(Instant.now());
            execution.setReconcileCount(execution.getReconcileCount() + 1);
            liveTradeExecutionRepository.save(execution);
            appendEvent(execution, "RECONCILING", execution.getExecutionState().name(),
                    "Binance reconciliation timed out.",
                    execution.getErrorCode(),
                    payloadOf("message", ex.getMessage(), "traceId", traceId));
            return liveTradingMapper.toDetail(execution,
                    liveTradeExecutionEventRepository.findByExecution_IdOrderByCreatedAtAsc(execution.getId()));
        } catch (WebClientResponseException ex) {
            execution.setExecutionState(LiveTradeExecutionState.RECONCILING);
            execution.setErrorCode("BINANCE_RECONCILE_REJECTED");
            execution.setErrorMessage(ex.getMessage());
            execution.setLastReconciledAt(Instant.now());
            execution.setUpdatedAt(Instant.now());
            execution.setReconcileCount(execution.getReconcileCount() + 1);
            liveTradeExecutionRepository.save(execution);
            appendEvent(execution, "RECONCILING", execution.getExecutionState().name(),
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

    private ReconciledOutcome resolveState(LiveTradeExecution execution,
            BinanceFuturesOrderResponse entry,
            BinanceFuturesAlgoOrderResponse stopLoss,
            BinanceFuturesAlgoOrderResponse takeProfit,
            List<BinanceFuturesAlgoOrderResponse> openAlgoOrders,
            BinanceFuturesOrderResponse emergencyClose,
            BinanceFuturesPositionRiskResponse position) {
        boolean hasPosition = hasOpenPosition(position);
        boolean stopLossActive = isAlgoActive(stopLoss, openAlgoOrders);
        boolean takeProfitActive = isAlgoActive(takeProfit, openAlgoOrders);
        boolean emergencyCloseFilled = isFilled(emergencyClose);
        boolean emergencyCloseWorking = isWorking(emergencyClose);
        boolean protectionTriggered = isTriggered(stopLoss) || isTriggered(takeProfit);
        BigDecimal positionQty = absolutePositionQty(position);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("positionQuantity", positionQty);
        summary.put("hasPosition", hasPosition);
        summary.put("stopLossActive", stopLossActive);
        summary.put("takeProfitActive", takeProfitActive);
        summary.put("emergencyCloseFilled", emergencyCloseFilled);
        summary.put("emergencyCloseWorking", emergencyCloseWorking);
        summary.put("protectionTriggered", protectionTriggered);
        summary.put("entryStatus", entry != null ? entry.getStatus() : null);
        summary.put("stopLossStatus", stopLoss != null ? stopLoss.getAlgoStatus() : null);
        summary.put("takeProfitStatus", takeProfit != null ? takeProfit.getAlgoStatus() : null);

        if (!hasPosition) {
            if (emergencyCloseFilled) {
                return new ReconciledOutcome(LiveTradeExecutionState.EMERGENCY_CLOSE_FILLED,
                        execution.getErrorCode(),
                        execution.getErrorMessage(),
                        summary);
            }
            if (protectionTriggered || isFilled(entry)) {
                return new ReconciledOutcome(LiveTradeExecutionState.RECONCILED,
                        execution.getErrorCode(),
                        execution.getErrorMessage(),
                        summary);
            }
            return new ReconciledOutcome(LiveTradeExecutionState.RECONCILING,
                    execution.getErrorCode(),
                    execution.getErrorMessage(),
                    summary);
        }

        if (stopLossActive && takeProfitActive) {
            return new ReconciledOutcome(LiveTradeExecutionState.PROTECTION_ACTIVE,
                    execution.getErrorCode(),
                    execution.getErrorMessage(),
                    summary);
        }
        if (stopLossActive && !takeProfitActive) {
            summary.put("downsideProtected", true);
            return new ReconciledOutcome(LiveTradeExecutionState.PROTECTION_FAILED,
                    execution.getErrorCode() != null ? execution.getErrorCode() : LiveTradingBlockerCodes.BINANCE_REJECTED,
                    execution.getErrorMessage() != null
                            ? execution.getErrorMessage()
                            : "Take-profit protection is missing, but stop-loss remains active.",
                    summary);
        }
        if (emergencyCloseWorking) {
            return new ReconciledOutcome(LiveTradeExecutionState.EMERGENCY_CLOSE_SUBMITTED,
                    execution.getErrorCode(),
                    execution.getErrorMessage(),
                    summary);
        }
        return new ReconciledOutcome(LiveTradeExecutionState.PROTECTION_FAILED,
                execution.getErrorCode() != null ? execution.getErrorCode() : LiveTradingBlockerCodes.BINANCE_REJECTED,
                execution.getErrorMessage() != null
                        ? execution.getErrorMessage()
                        : "Stop-loss protection is not active while Binance still reports an open position.",
                summary);
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

    private BinanceFuturesAlgoOrderResponse lookupAlgoOrder(String clientAlgoId,
            Long algoId,
            List<BinanceFuturesAlgoOrderResponse> openAlgoOrders) {
        BinanceFuturesAlgoOrderResponse openMatch = findOpenAlgoOrder(clientAlgoId, algoId, openAlgoOrders);
        try {
            BinanceFuturesAlgoOrderResponse queried = binanceClient.getAlgoOrder(clientAlgoId, algoId);
            return queried != null ? queried : openMatch;
        } catch (WebClientResponseException ex) {
            if (ex.getRawStatusCode() == 400 || ex.getRawStatusCode() == 404) {
                return openMatch;
            }
            throw ex;
        }
    }

    private List<BinanceFuturesAlgoOrderResponse> lookupOpenAlgoOrders(String symbol) {
        try {
            return binanceClient.getOpenAlgoOrders(symbol, "CONDITIONAL", null);
        } catch (WebClientResponseException ex) {
            if (ex.getRawStatusCode() == 400 || ex.getRawStatusCode() == 404) {
                return List.of();
            }
            throw ex;
        }
    }

    private BinanceFuturesAlgoOrderResponse findOpenAlgoOrder(String clientAlgoId,
            Long algoId,
            List<BinanceFuturesAlgoOrderResponse> openAlgoOrders) {
        if (openAlgoOrders == null) {
            return null;
        }
        return openAlgoOrders.stream()
                .filter(order -> {
                    if (algoId != null && algoId.equals(order.getAlgoId())) {
                        return true;
                    }
                    return clientAlgoId != null && clientAlgoId.equals(order.getClientAlgoId());
                })
                .findFirst()
                .orElse(null);
    }

    private BinanceFuturesPositionRiskResponse lookupPosition(String symbol) {
        List<BinanceFuturesPositionRiskResponse> positions = binanceClient.getPositionRisk(symbol);
        if (positions == null) {
            return null;
        }
        return positions.stream()
                .filter(position -> symbol.equalsIgnoreCase(position.getSymbol()))
                .filter(position -> "BOTH".equalsIgnoreCase(position.getPositionSide()))
                .findFirst()
                .orElseGet(() -> positions.stream()
                        .filter(position -> symbol.equalsIgnoreCase(position.getSymbol()))
                        .findFirst()
                        .orElse(null));
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

    private boolean hasOpenPosition(BinanceFuturesPositionRiskResponse position) {
        BigDecimal qty = absolutePositionQty(position);
        return qty.compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal absolutePositionQty(BinanceFuturesPositionRiskResponse position) {
        BigDecimal qty = signedDecimal(position != null ? position.getPositionAmt() : null);
        return qty == null ? BigDecimal.ZERO : qty.abs();
    }

    private boolean isFilled(BinanceFuturesOrderResponse response) {
        return response != null && "FILLED".equalsIgnoreCase(response.getStatus());
    }

    private boolean isWorking(BinanceFuturesOrderResponse response) {
        return response != null && ("NEW".equalsIgnoreCase(response.getStatus())
                || "PARTIALLY_FILLED".equalsIgnoreCase(response.getStatus()));
    }

    private boolean isAlgoActive(BinanceFuturesAlgoOrderResponse response, List<BinanceFuturesAlgoOrderResponse> openOrders) {
        if (response == null) {
            return false;
        }
        if (findOpenAlgoOrder(response.getClientAlgoId(), response.getAlgoId(), openOrders) != null) {
            return true;
        }
        return "NEW".equalsIgnoreCase(response.getAlgoStatus())
                || "WORKING".equalsIgnoreCase(response.getAlgoStatus());
    }

    private boolean isTriggered(BinanceFuturesAlgoOrderResponse response) {
        return response != null && response.getTriggerTime() != null && response.getTriggerTime() > 0;
    }

    private BigDecimal signedDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Object> orderSummary(BinanceFuturesOrderResponse response) {
        if (response == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderId", response.getOrderId());
        summary.put("clientOrderId", response.getClientOrderId());
        summary.put("status", response.getStatus());
        summary.put("executedQty", response.getExecutedQty());
        summary.put("origQty", response.getOrigQty());
        summary.put("avgPrice", response.getAvgPrice());
        summary.put("stopPrice", response.getStopPrice());
        summary.put("workingType", response.getWorkingType());
        summary.put("updateTime", response.getUpdateTime());
        return summary;
    }

    private Map<String, Object> algoSummary(BinanceFuturesAlgoOrderResponse response) {
        if (response == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("algoId", response.getAlgoId());
        summary.put("clientAlgoId", response.getClientAlgoId());
        summary.put("algoType", response.getAlgoType());
        summary.put("algoStatus", response.getAlgoStatus());
        summary.put("orderType", response.getOrderType());
        summary.put("symbol", response.getSymbol());
        summary.put("side", response.getSide());
        summary.put("positionSide", response.getPositionSide());
        summary.put("quantity", response.getQuantity());
        summary.put("executedQty", response.getExecutedQty());
        summary.put("avgPrice", response.getAvgPrice());
        summary.put("actualOrderId", response.getActualOrderId());
        summary.put("actualPrice", response.getActualPrice());
        summary.put("triggerPrice", response.getTriggerPrice());
        summary.put("workingType", response.getWorkingType());
        summary.put("closePosition", response.getClosePosition());
        summary.put("reduceOnly", response.getReduceOnly());
        summary.put("priceProtect", response.getPriceProtect());
        summary.put("createTime", response.getCreateTime());
        summary.put("updateTime", response.getUpdateTime());
        summary.put("triggerTime", response.getTriggerTime());
        return summary;
    }

    private Map<String, Object> positionSummary(BinanceFuturesPositionRiskResponse response) {
        if (response == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("symbol", response.getSymbol());
        summary.put("positionSide", response.getPositionSide());
        summary.put("positionAmt", response.getPositionAmt());
        summary.put("entryPrice", response.getEntryPrice());
        summary.put("breakEvenPrice", response.getBreakEvenPrice());
        summary.put("markPrice", response.getMarkPrice());
        summary.put("unRealizedProfit", response.getUnRealizedProfit());
        summary.put("notional", response.getNotional());
        summary.put("updateTime", response.getUpdateTime());
        return summary;
    }

    private Map<String, Object> mergeSection(Object existingValue, Map<String, Object> latest) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (existingValue instanceof Map<?, ?> existingMap) {
            existingMap.forEach((key, value) -> {
                if (key instanceof String stringKey) {
                    merged.put(stringKey, value);
                }
            });
        }
        if (latest != null) {
            merged.putAll(latest);
        }
        return merged;
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
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

    private record ReconciledOutcome(LiveTradeExecutionState state,
            String errorCode,
            String errorMessage,
            Map<String, Object> summary) {
    }
}
