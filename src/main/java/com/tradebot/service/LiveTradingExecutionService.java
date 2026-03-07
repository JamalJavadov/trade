package com.tradebot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
import com.tradebot.dto.BinanceFuturesOrderResponse;
import com.tradebot.dto.BinanceOrderFieldsDTO;
import com.tradebot.dto.LiveTradeBlockedReasonDTO;
import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.dto.LiveTradeExecutionRequestDTO;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionEvent;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.entity.LiveTradeTriggerMode;
import com.tradebot.entity.OrderFields;
import com.tradebot.entity.Recommendation;
import com.tradebot.repository.LiveTradeExecutionEventRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.security.LocalMutationGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LiveTradingExecutionService {

    private final RecommendationRepository recommendationRepository;
    private final LiveTradeExecutionRepository liveTradeExecutionRepository;
    private final LiveTradeExecutionEventRepository liveTradeExecutionEventRepository;
    private final LiveTradingPreflightService liveTradingPreflightService;
    private final LiveTradingReconciliationService liveTradingReconciliationService;
    private final LiveTradingMapper liveTradingMapper;
    private final LiveTradingBinanceDiagnosticsService liveTradingBinanceDiagnosticsService;
    private final BinanceClient binanceClient;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public LiveTradeExecutionDTO getExecution(UUID executionId) {
        LiveTradeExecution execution = liveTradeExecutionRepository.findById(executionId)
                .orElseThrow(() -> new NoSuchElementException("Live execution not found: " + executionId));
        return liveTradingMapper.toDetail(execution,
                liveTradeExecutionEventRepository.findByExecution_IdOrderByCreatedAtAsc(executionId));
    }

    @Transactional(readOnly = true)
    public List<LiveTradeExecutionDTO> listExecutions(UUID recommendationId, int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, 50));
        List<LiveTradeExecution> executions = recommendationId == null
                ? liveTradeExecutionRepository.findTop50ByOrderByCreatedAtDesc()
                : liveTradeExecutionRepository.findTop20ByRecommendation_IdOrderByCreatedAtDesc(recommendationId);

        return executions.stream()
                .limit(normalizedLimit)
                .map(execution -> liveTradingMapper.toDetail(execution,
                        liveTradeExecutionEventRepository.findByExecution_IdOrderByCreatedAtAsc(execution.getId())))
                .toList();
    }

    public LiveTradeExecutionDTO executeLive(UUID recommendationId,
            LiveTradeExecutionRequestDTO request,
            String operatorId,
            String traceId) {
        return executeLive(recommendationId, request, operatorId, traceId, null);
    }

    public LiveTradeExecutionDTO executeLive(UUID recommendationId,
            LiveTradeExecutionRequestDTO request,
            String operatorId,
            String traceId,
            LocalMutationGuard.LocalRequestCheck localRequestCheck) {
        UUID clientRequestId = requireClientRequestId(request);
        LiveTradeExecution existingAttempt = liveTradeExecutionRepository
                .findFirstByRecommendation_IdAndClientRequestId(recommendationId, clientRequestId)
                .orElse(null);
        if (existingAttempt != null) {
            appendEvent(existingAttempt, "DUPLICATE_REQUEST_IGNORED", existingAttempt.getExecutionState().name(),
                    "Manual live execution request reused an existing client request id.",
                    LiveTradingBlockerCodes.DUPLICATE_SUBMIT_BLOCKED,
                    payloadOf("traceId", traceId, "clientRequestId", clientRequestId));
            return liveTradingMapper.toDetail(existingAttempt,
                    liveTradeExecutionEventRepository.findByExecution_IdOrderByCreatedAtAsc(existingAttempt.getId()));
        }

        Recommendation recommendation = recommendationRepository.findById(recommendationId)
                .orElseThrow(() -> new NoSuchElementException("Recommendation not found: " + recommendationId));

        LiveTradeExecution execution = new LiveTradeExecution();
        execution.setRecommendation(recommendation);
        execution.setTriggerMode(LiveTradeTriggerMode.MANUAL_BUTTON);
        execution.setSymbol(recommendation.getSymbol());
        execution.setSide(recommendation.getSide());
        execution.setOperatorId(normalizeOperatorId(operatorId));
        execution.setTraceId(traceId);
        execution.setClientRequestId(clientRequestId);
        execution.setDryRun(false);
        execution.setExecutionState(LiveTradeExecutionState.REQUESTED);
        execution.setCreatedAt(Instant.now());
        execution.setUpdatedAt(Instant.now());
        execution.setPayloadSnapshotJson(writeJson(buildPayloadSnapshot(recommendation, request, traceId)));
        RequestedExecutionSaveResult saveResult = saveRequestedExecution(recommendationId, clientRequestId, execution);
        execution = saveResult.execution();
        if (!saveResult.created()) {
            appendEvent(execution, "DUPLICATE_REQUEST_IGNORED", execution.getExecutionState().name(),
                    "Manual live execution request reused an existing client request id.",
                    LiveTradingBlockerCodes.DUPLICATE_SUBMIT_BLOCKED,
                    payloadOf("traceId", traceId, "clientRequestId", clientRequestId));
            return liveTradingMapper.toDetail(execution,
                    liveTradeExecutionEventRepository.findByExecution_IdOrderByCreatedAtAsc(execution.getId()));
        }
        appendEvent(execution, "REQUESTED", execution.getExecutionState().name(),
                "Manual live execution requested from recommendation detail.",
                null,
                payloadOf("traceId", traceId, "clientRequestId", clientRequestId));

        LiveTradingPreflightDTO preflight = liveTradingPreflightService.evaluate(
                recommendationId,
                execution.getId(),
                localRequestCheck);
        execution.setPreflightJson(writeJson(preflight));
        execution.setUpdatedAt(Instant.now());

        if (!preflight.isExecutable()) {
            return completeBlockedExecution(execution, preflight);
        }

        OrderPayloads orderPayloads = parseOrderPayloads(recommendation.getOrderFields());
        if (orderPayloads == null) {
            preflight.getBlockedReasons().add(new LiveTradeBlockedReasonDTO(
                    LiveTradingBlockerCodes.MISSING_ORDER_FIELDS,
                    "Recommendation order payloads are missing or invalid.",
                    "exchangeValidation",
                    Map.of()));
            preflight.setAllowed(false);
            preflight.setExecutable(false);
            execution.setPreflightJson(writeJson(preflight));
            return completeBlockedExecution(execution, preflight);
        }

        execution.setExecutionState(LiveTradeExecutionState.SUBMITTING);
        execution.setSubmittedAt(Instant.now());
        execution.setUpdatedAt(Instant.now());
        execution.setEntryClientOrderId(clientOrderId(execution, "entry"));
        execution.setSlClientOrderId(clientOrderId(execution, "sl"));
        execution.setTpClientOrderId(clientOrderId(execution, "tp"));
        execution.setEmergencyCloseClientOrderId(clientOrderId(execution, "close"));
        liveTradeExecutionRepository.save(execution);
        appendEvent(execution, "SUBMITTING", execution.getExecutionState().name(),
                "Submitting live Binance Futures orders.",
                null,
                payloadOf("traceId", traceId));

        Map<String, Object> exchangeResponses = new LinkedHashMap<>();
        try {
            exchangeResponses.put("positionMode", binanceClient.ensureOneWayPositionMode());
            exchangeResponses.put("marginMode", binanceClient.ensureIsolatedMargin(recommendation.getSymbol()));
            exchangeResponses.put("leverage",
                    binanceClient.setLeverage(recommendation.getSymbol(), safeLeverageRecommendation(recommendation)));

            BinanceFuturesOrderResponse entryResponse = binanceClient.submitOrder(
                    buildEntryOrderParams(orderPayloads.entry(), execution.getEntryClientOrderId()));
            execution.setEntryOrderId(entryResponse.getOrderId());
            execution.setExecutionState(LiveTradeExecutionState.ENTRY_SUBMITTED);
            execution.setUpdatedAt(Instant.now());
            exchangeResponses.put("entry", responseSummary(entryResponse));
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            liveTradeExecutionRepository.save(execution);
            appendEvent(execution, "ENTRY_SUBMITTED", execution.getExecutionState().name(),
                    "Entry order submitted to Binance.",
                    null,
                    responseSummary(entryResponse));

            try {
                BinanceFuturesOrderResponse slResponse = binanceClient.submitOrder(
                        buildProtectionOrderParams(orderPayloads.sl(), execution.getSlClientOrderId()));
                execution.setSlOrderId(slResponse.getOrderId());
                exchangeResponses.put("stopLoss", responseSummary(slResponse));
                appendEvent(execution, "STOP_LOSS_SUBMITTED", "STOP_LOSS_SUBMITTED",
                        "Stop-loss order submitted to Binance.",
                        null,
                        responseSummary(slResponse));

                BinanceFuturesOrderResponse tpResponse = binanceClient.submitOrder(
                        buildProtectionOrderParams(orderPayloads.tp(), execution.getTpClientOrderId()));
                execution.setTpOrderId(tpResponse.getOrderId());
                exchangeResponses.put("takeProfit", responseSummary(tpResponse));
                appendEvent(execution, "TAKE_PROFIT_SUBMITTED", "TAKE_PROFIT_SUBMITTED",
                        "Take-profit order submitted to Binance.",
                        null,
                        responseSummary(tpResponse));

                execution.setExecutionState(LiveTradeExecutionState.PROTECTION_SUBMITTED);
                execution.setErrorCode(null);
                execution.setErrorMessage(null);
                execution.setExchangeResponseJson(writeJson(exchangeResponses));
                execution.setUpdatedAt(Instant.now());
                liveTradeExecutionRepository.save(execution);
                return liveTradingReconciliationService.reconcileExecution(execution.getId(),
                        execution.getOperatorId(),
                        traceId,
                        false);
            } catch (Exception protectionFailure) {
                LiveTradeBlockedReasonDTO failure = liveTradingBinanceDiagnosticsService
                        .classifyExecutionFailure(protectionFailure);
                log.warn("Protection order submission failed for execution {}: {}", execution.getId(),
                        protectionFailure.getMessage());
                exchangeResponses.put("protectionError", payloadOf(
                        "message", failure.getMessage(),
                        "type", protectionFailure.getClass().getSimpleName()));
                execution.setErrorCode(failure.getCode());
                execution.setErrorMessage("Protection order submission failed: " + failure.getMessage());
                execution.setExecutionState(LiveTradeExecutionState.PROTECTION_FAILED);
                appendEvent(execution, "PROTECTION_FAILED", execution.getExecutionState().name(),
                        "Protection order submission failed; attempting emergency close.",
                        execution.getErrorCode(),
                        payloadOf("message", failure.getMessage(), "details", failure.getDetails()));

                try {
                    BinanceFuturesOrderResponse emergencyClose = binanceClient.submitOrder(
                            buildEmergencyCloseParams(orderPayloads.entry(), execution.getEmergencyCloseClientOrderId()));
                    execution.setEmergencyCloseOrderId(emergencyClose.getOrderId());
                    execution.setExecutionState(LiveTradeExecutionState.EMERGENCY_CLOSE_SUBMITTED);
                    exchangeResponses.put("emergencyClose", responseSummary(emergencyClose));
                    appendEvent(execution, "EMERGENCY_CLOSE_SUBMITTED", execution.getExecutionState().name(),
                            "Emergency reduce-only close submitted after protection failure.",
                            null,
                            responseSummary(emergencyClose));
                    execution.setExchangeResponseJson(writeJson(exchangeResponses));
                    execution.setUpdatedAt(Instant.now());
                    liveTradeExecutionRepository.save(execution);
                    return liveTradingReconciliationService.reconcileExecution(execution.getId(),
                            execution.getOperatorId(),
                            traceId,
                            false);
                } catch (Exception emergencyCloseFailure) {
                    LiveTradeBlockedReasonDTO emergencyFailure = liveTradingBinanceDiagnosticsService
                            .classifyExecutionFailure(emergencyCloseFailure);
                    execution.setExecutionState(LiveTradeExecutionState.EMERGENCY_CLOSE_FAILED);
                    execution.setErrorCode(emergencyFailure.getCode());
                    execution.setErrorMessage("Protection order failed and emergency close also failed: "
                            + emergencyFailure.getMessage());
                    exchangeResponses.put("emergencyCloseError", Map.of(
                            "message", emergencyFailure.getMessage(),
                            "type", emergencyCloseFailure.getClass().getSimpleName()));
                    execution.setExchangeResponseJson(writeJson(exchangeResponses));
                    execution.setCompletedAt(Instant.now());
                    execution.setUpdatedAt(Instant.now());
                    liveTradeExecutionRepository.save(execution);
                    appendEvent(execution, "EMERGENCY_CLOSE_FAILED", execution.getExecutionState().name(),
                            "Emergency close failed after protection order failure.",
                            execution.getErrorCode(),
                            payloadOf("message", emergencyFailure.getMessage(), "details", emergencyFailure.getDetails()));
                    return getExecution(execution.getId());
                }
            }
        } catch (WebClientRequestException ex) {
            LiveTradeBlockedReasonDTO failure = liveTradingBinanceDiagnosticsService.classifyExecutionFailure(ex);
            execution.setExecutionState(LiveTradeExecutionState.PENDING_RECONCILE);
            execution.setErrorCode(LiveTradingBlockerCodes.UPSTREAM_TIMEOUT);
            execution.setErrorMessage("Submission timed out or lost connectivity. Reconciliation required.");
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            execution.setUpdatedAt(Instant.now());
            liveTradeExecutionRepository.save(execution);
            appendEvent(execution, "PENDING_RECONCILE", execution.getExecutionState().name(),
                    "Submission outcome is unknown due to network timeout. Scheduled reconciliation required.",
                    failure.getCode(),
                    payloadOf("message", failure.getMessage()));
            return getExecution(execution.getId());
        } catch (WebClientResponseException ex) {
            LiveTradeBlockedReasonDTO failure = liveTradingBinanceDiagnosticsService.classifyExecutionFailure(ex);
            execution.setExecutionState(LiveTradeExecutionState.FAILED);
            execution.setErrorCode(failure.getCode());
            execution.setErrorMessage(failure.getMessage());
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            execution.setCompletedAt(Instant.now());
            execution.setUpdatedAt(Instant.now());
            liveTradeExecutionRepository.save(execution);
            appendEvent(execution, "FAILED", execution.getExecutionState().name(),
                    "Binance rejected the live execution request.",
                    execution.getErrorCode(),
                    payloadOf(
                            "status", ex.getRawStatusCode(),
                            "message", failure.getMessage(),
                            "details", failure.getDetails()));
            return getExecution(execution.getId());
        } catch (Exception ex) {
            LiveTradeBlockedReasonDTO failure = liveTradingBinanceDiagnosticsService.classifyExecutionFailure(ex);
            execution.setExecutionState(LiveTradeExecutionState.FAILED);
            execution.setErrorCode(failure.getCode());
            execution.setErrorMessage(failure.getMessage());
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            execution.setCompletedAt(Instant.now());
            execution.setUpdatedAt(Instant.now());
            liveTradeExecutionRepository.save(execution);
            appendEvent(execution, "FAILED", execution.getExecutionState().name(),
                    "Live execution failed before Binance confirmation.",
                    execution.getErrorCode(),
                    payloadOf("message", failure.getMessage(), "details", failure.getDetails()));
            return getExecution(execution.getId());
        }
    }

    private LiveTradeExecutionDTO completeBlockedExecution(LiveTradeExecution execution, LiveTradingPreflightDTO preflight) {
        execution.setExecutionState(LiveTradeExecutionState.BLOCKED);
        execution.setErrorCode(firstBlockedCode(preflight.getBlockedReasons()));
        execution.setErrorMessage(firstBlockedMessage(preflight.getBlockedReasons()));
        execution.setCompletedAt(Instant.now());
        execution.setUpdatedAt(Instant.now());
        liveTradeExecutionRepository.save(execution);
        appendEvent(execution, "BLOCKED", execution.getExecutionState().name(),
                "Preflight blocked live execution.",
                execution.getErrorCode(),
                payloadOf("blockedReasons", preflight.getBlockedReasons()));
        return getExecution(execution.getId());
    }

    private UUID requireClientRequestId(LiveTradeExecutionRequestDTO request) {
        if (request == null || request.getClientRequestId() == null) {
            throw new IllegalArgumentException("clientRequestId is required");
        }
        return request.getClientRequestId();
    }

    private RequestedExecutionSaveResult saveRequestedExecution(UUID recommendationId,
            UUID clientRequestId,
            LiveTradeExecution execution) {
        try {
            return new RequestedExecutionSaveResult(liveTradeExecutionRepository.save(execution), true);
        } catch (DataIntegrityViolationException ex) {
            LiveTradeExecution existing = liveTradeExecutionRepository
                    .findFirstByRecommendation_IdAndClientRequestId(recommendationId, clientRequestId)
                    .orElseThrow(() -> ex);
            return new RequestedExecutionSaveResult(existing, false);
        }
    }

    private Map<String, Object> buildPayloadSnapshot(Recommendation recommendation,
            LiveTradeExecutionRequestDTO request,
            String traceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recommendationId", recommendation.getId());
        payload.put("scanRunId", recommendation.getScanRun() != null ? recommendation.getScanRun().getId() : null);
        payload.put("symbol", recommendation.getSymbol());
        payload.put("side", recommendation.getSide());
        payload.put("request", payloadOf(
                "clientRequestId", request != null ? request.getClientRequestId() : null,
                "operatorNote", request != null ? request.getOperatorNote() : null));
        payload.put("traceId", traceId);
        OrderFields orderFields = recommendation.getOrderFields();
        if (orderFields != null) {
            payload.put("entryOrder", liveTradingMapper.readJson(orderFields.getEntryOrderJson()));
            payload.put("slOrder", liveTradingMapper.readJson(orderFields.getSlOrderJson()));
            payload.put("tpOrder", liveTradingMapper.readJson(orderFields.getTpOrderJson()));
            payload.put("leverageRecommendation", orderFields.getLeverageRecommendation());
            payload.put("marginMode", orderFields.getMarginMode());
            payload.put("positionMode", orderFields.getPositionMode());
            payload.put("workingType", orderFields.getWorkingType());
        }
        return payload;
    }

    private OrderPayloads parseOrderPayloads(OrderFields orderFields) {
        if (orderFields == null) {
            return null;
        }
        try {
            return new OrderPayloads(
                    objectMapper.readValue(orderFields.getEntryOrderJson(), BinanceOrderFieldsDTO.class),
                    objectMapper.readValue(orderFields.getSlOrderJson(), BinanceOrderFieldsDTO.class),
                    objectMapper.readValue(orderFields.getTpOrderJson(), BinanceOrderFieldsDTO.class));
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, String> buildEntryOrderParams(BinanceOrderFieldsDTO entryOrder, String clientOrderId) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("symbol", entryOrder.getSymbol());
        params.put("side", entryOrder.getSide());
        params.put("type", "MARKET");
        params.put("quantity", entryOrder.getQuantity().toPlainString());
        params.put("newClientOrderId", clientOrderId);
        return params;
    }

    private Map<String, String> buildProtectionOrderParams(BinanceOrderFieldsDTO order, String clientOrderId) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("symbol", order.getSymbol());
        params.put("side", order.getSide());
        params.put("type", order.getType());
        params.put("stopPrice", order.getStopPrice().toPlainString());
        params.put("closePosition", String.valueOf(Boolean.TRUE.equals(order.getClosePosition())));
        if (order.getWorkingType() != null && !order.getWorkingType().isBlank()) {
            params.put("workingType", order.getWorkingType());
        }
        if (order.getTimeInForce() != null && !order.getTimeInForce().isBlank()) {
            params.put("timeInForce", order.getTimeInForce());
        }
        // closePosition=true already carries reduce-only semantics for Binance stop-market orders.
        params.put("newClientOrderId", clientOrderId);
        return params;
    }

    private Map<String, String> buildEmergencyCloseParams(BinanceOrderFieldsDTO entryOrder, String clientOrderId) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("symbol", entryOrder.getSymbol());
        params.put("side", oppositeSide(entryOrder.getSide()));
        params.put("type", "MARKET");
        params.put("quantity", entryOrder.getQuantity().toPlainString());
        params.put("reduceOnly", "true");
        params.put("newClientOrderId", clientOrderId);
        return params;
    }

    private String oppositeSide(String side) {
        return "BUY".equalsIgnoreCase(side) ? "SELL" : "BUY";
    }

    private int safeLeverageRecommendation(Recommendation recommendation) {
        OrderFields orderFields = recommendation.getOrderFields();
        Integer leverage = orderFields != null ? orderFields.getLeverageRecommendation() : null;
        return leverage != null && leverage > 0 ? leverage : 1;
    }

    private String clientOrderId(LiveTradeExecution execution, String suffix) {
        String compact = execution.getId().toString().replace("-", "");
        String shortId = compact.substring(0, Math.min(12, compact.length()));
        return "tb-live-" + shortId + "-" + suffix;
    }

    private String normalizeOperatorId(String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            return "local-operator";
        }
        return operatorId.trim();
    }

    private Map<String, Object> responseSummary(BinanceFuturesOrderResponse response) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderId", response.getOrderId());
        summary.put("clientOrderId", response.getClientOrderId());
        summary.put("status", response.getStatus());
        summary.put("executedQty", response.getExecutedQty());
        summary.put("avgPrice", response.getAvgPrice());
        summary.put("cumQuote", response.getCumQuote());
        summary.put("stopPrice", response.getStopPrice());
        summary.put("workingType", response.getWorkingType());
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

    private String firstBlockedCode(Collection<LiveTradeBlockedReasonDTO> blockedReasons) {
        return blockedReasons == null || blockedReasons.isEmpty() ? "BLOCKED" : blockedReasons.iterator().next().getCode();
    }

    private String firstBlockedMessage(Collection<LiveTradeBlockedReasonDTO> blockedReasons) {
        return blockedReasons == null || blockedReasons.isEmpty()
                ? "Execution blocked by preflight."
                : blockedReasons.iterator().next().getMessage();
    }

    private String summarizeFailure(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return error == null ? "Unknown error" : error.getClass().getSimpleName();
        }
        return error.getMessage();
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

    private record OrderPayloads(BinanceOrderFieldsDTO entry, BinanceOrderFieldsDTO sl, BinanceOrderFieldsDTO tp) {
    }

    private record RequestedExecutionSaveResult(LiveTradeExecution execution, boolean created) {
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
