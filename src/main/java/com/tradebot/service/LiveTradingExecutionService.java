package com.tradebot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
import com.tradebot.dto.BinanceExchangeInfoResponse;
import com.tradebot.dto.BinanceFuturesAlgoOrderResponse;
import com.tradebot.dto.BinanceFuturesOrderResponse;
import com.tradebot.dto.BinanceFuturesPositionRiskResponse;
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
import java.math.RoundingMode;
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

    private static final String PROTECTION_ALGO_TYPE = "CONDITIONAL";
    private static final int ENTRY_FILL_LOOKUP_ATTEMPTS = 5;
    private static final long ENTRY_FILL_LOOKUP_DELAY_MS = 400L;

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
            exchangeResponses.put("entry", responseSummary(entryResponse));
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            execution.setExecutionState(LiveTradeExecutionState.ENTRY_SUBMITTED);
            execution.setUpdatedAt(Instant.now());
            liveTradeExecutionRepository.save(execution);
            appendEvent(execution, "ENTRY_SUBMITTED", execution.getExecutionState().name(),
                    "Entry order submitted to Binance.",
                    null,
                    responseSummary(entryResponse));

            EntryFillResolution entryFill = resolveEntryFill(execution, orderPayloads.entry(), entryResponse);
            exchangeResponses.put("entry", entryFill.entrySummary());
            exchangeResponses.put("position", entryFill.positionSummary());
            execution.setExecutionState(entryFill.executionState());
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            execution.setUpdatedAt(Instant.now());
            liveTradeExecutionRepository.save(execution);
            appendEvent(execution, execution.getExecutionState().name(), execution.getExecutionState().name(),
                    entryFill.executionState() == LiveTradeExecutionState.ENTRY_PARTIALLY_FILLED
                            ? "Entry fill detected and position is partially filled."
                            : "Entry fill detected from Binance truth.",
                    null,
                    payloadOf(
                            "source", entryFill.source(),
                            "filledQuantity", entryFill.filledQuantity(),
                            "avgPrice", entryFill.avgPrice(),
                            "positionQuantity", entryFill.positionQuantity(),
                            "positionEntryPrice", entryFill.positionEntryPrice(),
                            "orderStatus", entryFill.orderStatus()));

            if (!entryFill.hasPosition()) {
                execution.setExecutionState(LiveTradeExecutionState.RECONCILING);
                execution.setErrorCode(LiveTradingBlockerCodes.ENTRY_FILL_UNRESOLVED);
                execution.setErrorMessage(
                        "Entry was accepted but Binance fill/position truth is not yet usable for protection.");
                execution.setExchangeResponseJson(writeJson(exchangeResponses));
                execution.setUpdatedAt(Instant.now());
                liveTradeExecutionRepository.save(execution);
                appendEvent(execution, "RECONCILING", execution.getExecutionState().name(),
                        "Entry accepted, but fill state is unresolved. Protection submission was deferred.",
                        execution.getErrorCode(),
                        payloadOf("entry", entryFill.entrySummary(), "position", entryFill.positionSummary()));
                return getExecution(execution.getId());
            }

            BinanceExchangeInfoResponse.SymbolInfo symbolInfo = binanceClient.getSymbolInfo(recommendation.getSymbol())
                    .orElse(null);
            ProtectionLegSubmission stopLoss = submitProtectionLeg(
                    "stopLoss",
                    "STOP_LOSS",
                    orderPayloads.entry(),
                    orderPayloads.sl(),
                    execution.getSlClientOrderId(),
                    symbolInfo,
                    entryFill);
            exchangeResponses.put("stopLoss", stopLoss.summary());
            persistProtectionLegEvent(execution, stopLoss);

            ProtectionLegSubmission takeProfit = submitProtectionLeg(
                    "takeProfit",
                    "TAKE_PROFIT",
                    orderPayloads.entry(),
                    orderPayloads.tp(),
                    execution.getTpClientOrderId(),
                    symbolInfo,
                    entryFill);
            exchangeResponses.put("takeProfit", takeProfit.summary());
            persistProtectionLegEvent(execution, takeProfit);

            execution.setSlOrderId(stopLoss.algoId());
            execution.setTpOrderId(takeProfit.algoId());
            exchangeResponses.put("position", entryFill.positionSummary());

            if (stopLoss.success() && takeProfit.success()) {
                execution.setExecutionState(LiveTradeExecutionState.PROTECTION_ACTIVE);
                execution.setExchangeResponseJson(writeJson(exchangeResponses));
                execution.setUpdatedAt(Instant.now());
                liveTradeExecutionRepository.save(execution);
                appendEvent(execution, "PROTECTION_ACTIVE", execution.getExecutionState().name(),
                        "Both protection legs were accepted by Binance.",
                        null,
                        payloadOf(
                                "filledQuantity", entryFill.filledQuantity(),
                                "avgPrice", entryFill.avgPrice(),
                                "positionQuantity", entryFill.positionQuantity()));
                return liveTradingReconciliationService.reconcileExecution(execution.getId(),
                        execution.getOperatorId(),
                        traceId,
                        false);
            }

            ProtectionFailureSummary failure = summarizeProtectionFailure(stopLoss, takeProfit);
            exchangeResponses.put("protectionFailure", failure.summary());
            execution.setExecutionState(LiveTradeExecutionState.PROTECTION_FAILED);
            execution.setErrorCode(failure.errorCode());
            execution.setErrorMessage(failure.errorMessage());
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            execution.setUpdatedAt(Instant.now());
            liveTradeExecutionRepository.save(execution);
            appendEvent(execution, "PROTECTION_FAILED", execution.getExecutionState().name(),
                    failure.operatorMessage(),
                    failure.errorCode(),
                    failure.summary());

            if (failure.downsideProtected()) {
                return liveTradingReconciliationService.reconcileExecution(execution.getId(),
                        execution.getOperatorId(),
                        traceId,
                        false);
            }

            BigDecimal emergencyCloseQuantity = normalizeEmergencyCloseQuantity(entryFill.positionQuantity(), symbolInfo);
            if (emergencyCloseQuantity == null || emergencyCloseQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                execution.setExecutionState(LiveTradeExecutionState.RECONCILING);
                execution.setExchangeResponseJson(writeJson(exchangeResponses));
                execution.setUpdatedAt(Instant.now());
                liveTradeExecutionRepository.save(execution);
                appendEvent(execution, "RECONCILING", execution.getExecutionState().name(),
                        "Protection failed and Binance position size could not be normalized for emergency close.",
                        failure.errorCode(),
                        payloadOf(
                                "positionQuantity", entryFill.positionQuantity(),
                                "rawPosition", entryFill.positionSummary()));
                return liveTradingReconciliationService.reconcileExecution(execution.getId(),
                        execution.getOperatorId(),
                        traceId,
                        false);
            }

            try {
                BinanceFuturesOrderResponse emergencyClose = binanceClient.submitOrder(
                        buildEmergencyCloseParams(orderPayloads.entry(),
                                execution.getEmergencyCloseClientOrderId(),
                                emergencyCloseQuantity));
                execution.setEmergencyCloseOrderId(emergencyClose.getOrderId());
                execution.setExecutionState(LiveTradeExecutionState.EMERGENCY_CLOSE_SUBMITTED);
                exchangeResponses.put("emergencyClose", responseSummary(emergencyClose));
                execution.setExchangeResponseJson(writeJson(exchangeResponses));
                execution.setUpdatedAt(Instant.now());
                liveTradeExecutionRepository.save(execution);
                appendEvent(execution, "EMERGENCY_CLOSE_SUBMITTED", execution.getExecutionState().name(),
                        "Emergency reduce-only close submitted after stop-loss protection failure.",
                        null,
                        payloadOf(
                                "positionQuantity", emergencyCloseQuantity,
                                "response", responseSummary(emergencyClose)));
                return liveTradingReconciliationService.reconcileExecution(execution.getId(),
                        execution.getOperatorId(),
                        traceId,
                        false);
            } catch (Exception emergencyCloseFailure) {
                LiveTradeBlockedReasonDTO emergencyFailure = liveTradingBinanceDiagnosticsService
                        .classifyExecutionFailure(emergencyCloseFailure);
                exchangeResponses.put("emergencyClose", payloadOf(
                        "submitted", false,
                        "message", emergencyFailure.getMessage(),
                        "errorCode", emergencyFailure.getCode(),
                        "details", emergencyFailure.getDetails(),
                        "positionQuantity", emergencyCloseQuantity));
                execution.setExecutionState(LiveTradeExecutionState.EMERGENCY_CLOSE_FAILED);
                execution.setErrorCode(emergencyFailure.getCode());
                execution.setErrorMessage("Protection failed and emergency close also failed: "
                        + emergencyFailure.getMessage());
                execution.setExchangeResponseJson(writeJson(exchangeResponses));
                execution.setCompletedAt(Instant.now());
                execution.setUpdatedAt(Instant.now());
                liveTradeExecutionRepository.save(execution);
                appendEvent(execution, "EMERGENCY_CLOSE_FAILED", execution.getExecutionState().name(),
                        "Emergency close failed after stop-loss protection failure.",
                        execution.getErrorCode(),
                        payloadOf("message", emergencyFailure.getMessage(), "details", emergencyFailure.getDetails()));
                return getExecution(execution.getId());
            }
        } catch (WebClientRequestException ex) {
            LiveTradeBlockedReasonDTO failure = liveTradingBinanceDiagnosticsService.classifyExecutionFailure(ex);
            execution.setExecutionState(LiveTradeExecutionState.RECONCILING);
            execution.setErrorCode(LiveTradingBlockerCodes.UPSTREAM_TIMEOUT);
            execution.setErrorMessage("Submission timed out or lost connectivity. Reconciliation required.");
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            execution.setUpdatedAt(Instant.now());
            liveTradeExecutionRepository.save(execution);
            appendEvent(execution, "RECONCILING", execution.getExecutionState().name(),
                    "Submission outcome is unknown due to network timeout. Reconciliation required.",
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
        params.put("newOrderRespType", "RESULT");
        return params;
    }

    private Map<String, String> buildProtectionOrderParams(BinanceOrderFieldsDTO order, String clientAlgoId) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("algoType", PROTECTION_ALGO_TYPE);
        params.put("symbol", order.getSymbol());
        params.put("side", order.getSide());
        params.put("positionSide", "BOTH");
        params.put("type", order.getType());
        params.put("triggerPrice", order.getStopPrice().toPlainString());
        params.put("closePosition", "true");
        params.put("priceProtect", "FALSE");
        params.put("clientAlgoId", clientAlgoId);
        params.put("newOrderRespType", "RESULT");
        if (order.getWorkingType() != null && !order.getWorkingType().isBlank()) {
            params.put("workingType", order.getWorkingType());
        }
        return params;
    }

    private Map<String, String> buildEmergencyCloseParams(BinanceOrderFieldsDTO entryOrder,
            String clientOrderId,
            BigDecimal quantity) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("symbol", entryOrder.getSymbol());
        params.put("side", oppositeSide(entryOrder.getSide()));
        params.put("type", "MARKET");
        params.put("quantity", quantity.toPlainString());
        params.put("positionSide", "BOTH");
        params.put("reduceOnly", "true");
        params.put("newClientOrderId", clientOrderId);
        params.put("newOrderRespType", "RESULT");
        return params;
    }

    private ProtectionLegSubmission submitProtectionLeg(String legKey,
            String legLabel,
            BinanceOrderFieldsDTO entryOrder,
            BinanceOrderFieldsDTO protectionOrder,
            String clientAlgoId,
            BinanceExchangeInfoResponse.SymbolInfo symbolInfo,
            EntryFillResolution entryFill) {
        ProtectionValidation validation = validateProtectionOrder(legKey, entryOrder, protectionOrder, symbolInfo, entryFill);
        if (!validation.valid()) {
            return ProtectionLegSubmission.failure(legKey,
                    legLabel,
                    clientAlgoId,
                    LiveTradingBlockerCodes.PROTECTION_ORDER_INVALID,
                    validation.message(),
                    validation.details());
        }
        try {
            BinanceFuturesAlgoOrderResponse response = binanceClient.submitAlgoOrder(
                    buildProtectionOrderParams(protectionOrder, clientAlgoId));
            return ProtectionLegSubmission.success(legKey, legLabel, clientAlgoId, response);
        } catch (Exception ex) {
            LiveTradeBlockedReasonDTO failure = liveTradingBinanceDiagnosticsService.classifyExecutionFailure(ex);
            return ProtectionLegSubmission.failure(legKey,
                    legLabel,
                    clientAlgoId,
                    failure.getCode(),
                    failure.getMessage(),
                    failure.getDetails());
        }
    }

    private EntryFillResolution resolveEntryFill(LiveTradeExecution execution,
            BinanceOrderFieldsDTO entryOrder,
            BinanceFuturesOrderResponse entryResponse) {
        EntryFillResolution fromResponse = entryFillFromOrder("entryResult", entryResponse, null);
        if (fromResponse.hasPosition()) {
            return fromResponse;
        }

        EntryFillResolution latest = fromResponse;
        for (int attempt = 0; attempt < ENTRY_FILL_LOOKUP_ATTEMPTS; attempt++) {
            BinanceFuturesOrderResponse orderLookup = null;
            try {
                orderLookup = binanceClient.getOrder(execution.getSymbol(),
                        execution.getEntryClientOrderId(),
                        execution.getEntryOrderId());
            } catch (WebClientResponseException ex) {
                if (ex.getRawStatusCode() != 400 && ex.getRawStatusCode() != 404) {
                    throw ex;
                }
            }
            EntryFillResolution fromOrderLookup = entryFillFromOrder("orderLookup", orderLookup, null);
            if (fromOrderLookup.hasPosition()) {
                return fromOrderLookup;
            }
            latest = fromOrderLookup.orderStatus() != null ? fromOrderLookup : latest;

            BinanceFuturesPositionRiskResponse positionRisk = lookupPosition(execution.getSymbol(), entryOrder.getSide());
            EntryFillResolution fromPosition = entryFillFromPosition("positionRisk", positionRisk, orderLookup);
            if (fromPosition.hasPosition()) {
                return fromPosition;
            }
            latest = fromPosition.positionQuantity().compareTo(BigDecimal.ZERO) > 0 ? fromPosition : latest;
            if (attempt + 1 < ENTRY_FILL_LOOKUP_ATTEMPTS) {
                pauseBetweenFillLookups();
            }
        }
        return latest;
    }

    private EntryFillResolution entryFillFromOrder(String source,
            BinanceFuturesOrderResponse response,
            BinanceFuturesPositionRiskResponse positionRisk) {
        BigDecimal executedQty = positiveDecimal(response != null ? response.getExecutedQty() : null);
        BigDecimal avgPrice = positiveDecimal(response != null ? response.getAvgPrice() : null);
        BigDecimal positionQuantity = positionRiskQuantity(positionRisk);
        BigDecimal positionEntryPrice = positiveDecimal(positionRisk != null ? positionRisk.getEntryPrice() : null);
        String orderStatus = response != null ? response.getStatus() : null;
        if (positionQuantity == null || positionQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            positionQuantity = executedQty;
        }
        if (positionEntryPrice == null) {
            positionEntryPrice = avgPrice;
        }
        return new EntryFillResolution(
                source,
                resolvedEntryState(orderStatus, positionQuantity),
                nonNegative(positionQuantity),
                positiveOrZero(avgPrice),
                positiveOrZero(positionEntryPrice),
                orderStatus,
                responseSummary(response),
                positionSummary(positionRisk));
    }

    private EntryFillResolution entryFillFromPosition(String source,
            BinanceFuturesPositionRiskResponse positionRisk,
            BinanceFuturesOrderResponse response) {
        BigDecimal positionQuantity = positionRiskQuantity(positionRisk);
        BigDecimal positionEntryPrice = positiveDecimal(positionRisk != null ? positionRisk.getEntryPrice() : null);
        BigDecimal avgPrice = positiveDecimal(response != null ? response.getAvgPrice() : null);
        String orderStatus = response != null ? response.getStatus() : null;
        return new EntryFillResolution(
                source,
                resolvedEntryState(orderStatus, positionQuantity),
                nonNegative(positionQuantity),
                positiveOrZero(avgPrice),
                positiveOrZero(positionEntryPrice),
                orderStatus,
                responseSummary(response),
                positionSummary(positionRisk));
    }

    private LiveTradeExecutionState resolvedEntryState(String orderStatus, BigDecimal positionQuantity) {
        if (positionQuantity == null || positionQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return LiveTradeExecutionState.ENTRY_SUBMITTED;
        }
        if ("FILLED".equalsIgnoreCase(orderStatus)) {
            return LiveTradeExecutionState.ENTRY_FILLED;
        }
        return LiveTradeExecutionState.ENTRY_PARTIALLY_FILLED;
    }

    private ProtectionValidation validateProtectionOrder(String legKey,
            BinanceOrderFieldsDTO entryOrder,
            BinanceOrderFieldsDTO protectionOrder,
            BinanceExchangeInfoResponse.SymbolInfo symbolInfo,
            EntryFillResolution entryFill) {
        if (protectionOrder == null) {
            return ProtectionValidation.invalid("Protection payload is missing.", Map.of("leg", legKey));
        }
        if (protectionOrder.getStopPrice() == null || protectionOrder.getStopPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return ProtectionValidation.invalid("Trigger price is missing or invalid.",
                    payloadOf("leg", legKey, "type", protectionOrder.getType()));
        }
        if (!Boolean.TRUE.equals(protectionOrder.getClosePosition())) {
            return ProtectionValidation.invalid("Protection must use closePosition=true.",
                    payloadOf("leg", legKey, "closePosition", protectionOrder.getClosePosition()));
        }
        if (!"STOP_MARKET".equalsIgnoreCase(protectionOrder.getType())
                && !"TAKE_PROFIT_MARKET".equalsIgnoreCase(protectionOrder.getType())) {
            return ProtectionValidation.invalid("Unsupported protection order type for close-all algo order.",
                    payloadOf("leg", legKey, "type", protectionOrder.getType()));
        }
        if (!oppositeSide(entryOrder.getSide()).equalsIgnoreCase(protectionOrder.getSide())) {
            return ProtectionValidation.invalid("Protection side must be opposite the entry side.",
                    payloadOf("leg", legKey, "entrySide", entryOrder.getSide(), "side", protectionOrder.getSide()));
        }
        BigDecimal tickSize = symbolInfo != null ? nonZero(symbolInfo.getTickSize()) : null;
        if (tickSize != null && !alignsToIncrement(protectionOrder.getStopPrice(), tickSize)) {
            return ProtectionValidation.invalid("Trigger price does not align to Binance tick size.",
                    payloadOf("leg", legKey, "triggerPrice", protectionOrder.getStopPrice(), "tickSize", tickSize));
        }
        BigDecimal referencePrice = entryFill.referencePrice();
        if (referencePrice != null && referencePrice.compareTo(BigDecimal.ZERO) > 0) {
            boolean longEntry = "BUY".equalsIgnoreCase(entryOrder.getSide());
            boolean validDirection = switch (normalizeType(protectionOrder.getType())) {
                case "STOP_MARKET" -> longEntry
                        ? protectionOrder.getStopPrice().compareTo(referencePrice) < 0
                        : protectionOrder.getStopPrice().compareTo(referencePrice) > 0;
                case "TAKE_PROFIT_MARKET" -> longEntry
                        ? protectionOrder.getStopPrice().compareTo(referencePrice) > 0
                        : protectionOrder.getStopPrice().compareTo(referencePrice) < 0;
                default -> false;
            };
            if (!validDirection) {
                return ProtectionValidation.invalid("Trigger direction is invalid for the resolved entry price.",
                        payloadOf(
                                "leg", legKey,
                                "type", protectionOrder.getType(),
                                "triggerPrice", protectionOrder.getStopPrice(),
                                "referencePrice", referencePrice,
                                "entrySide", entryOrder.getSide()));
            }
        }
        return ProtectionValidation.passed();
    }

    private void persistProtectionLegEvent(LiveTradeExecution execution, ProtectionLegSubmission submission) {
        if (submission.success()) {
            appendEvent(execution,
                    submission.legKey().equals("stopLoss") ? "STOP_LOSS_SUBMITTED" : "TAKE_PROFIT_SUBMITTED",
                    "PROTECTION_SUBMITTING",
                    submission.legLabel() + " protection order submitted to Binance.",
                    null,
                    submission.summary());
            return;
        }
        appendEvent(execution,
                submission.legKey().equals("stopLoss") ? "STOP_LOSS_FAILED" : "TAKE_PROFIT_FAILED",
                "PROTECTION_SUBMITTING",
                submission.legLabel() + " protection order failed before active protection was confirmed.",
                submission.errorCode(),
                submission.summary());
    }

    private ProtectionFailureSummary summarizeProtectionFailure(ProtectionLegSubmission stopLoss,
            ProtectionLegSubmission takeProfit) {
        boolean downsideProtected = stopLoss.success();
        boolean emergencyCloseRequired = !downsideProtected;
        if (!stopLoss.success() && !takeProfit.success()) {
            return new ProtectionFailureSummary(
                    stopLoss.errorCode() != null ? stopLoss.errorCode() : takeProfit.errorCode(),
                    "Stop-loss and take-profit protection both failed.",
                    "Protection failed: both stop-loss and take-profit orders were rejected or invalid.",
                    downsideProtected,
                    emergencyCloseRequired,
                    List.of("stopLoss", "takeProfit"),
                    payloadOf("stopLoss", stopLoss.summary(), "takeProfit", takeProfit.summary()));
        }
        if (!stopLoss.success()) {
            return new ProtectionFailureSummary(
                    stopLoss.errorCode(),
                    "Stop-loss protection failed.",
                    "Protection failed: stop-loss order was not active, so emergency close is required.",
                    false,
                    true,
                    List.of("stopLoss"),
                    payloadOf("stopLoss", stopLoss.summary(), "takeProfit", takeProfit.summary()));
        }
        return new ProtectionFailureSummary(
                takeProfit.errorCode(),
                "Take-profit protection failed while stop-loss remains active.",
                "Take-profit protection failed, but stop-loss remains active.",
                true,
                false,
                List.of("takeProfit"),
                payloadOf("stopLoss", stopLoss.summary(), "takeProfit", takeProfit.summary()));
    }

    private BigDecimal normalizeEmergencyCloseQuantity(BigDecimal quantity,
            BinanceExchangeInfoResponse.SymbolInfo symbolInfo) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal stepSize = symbolInfo != null ? nonZero(symbolInfo.getMarketStepSize()) : null;
        if (stepSize == null && symbolInfo != null) {
            stepSize = nonZero(symbolInfo.getStepSize());
        }
        if (stepSize == null) {
            return quantity.stripTrailingZeros();
        }
        BigDecimal rounded = quantity.divide(stepSize, 0, RoundingMode.DOWN).multiply(stepSize);
        if (rounded.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return rounded.stripTrailingZeros();
    }

    private BinanceFuturesPositionRiskResponse lookupPosition(String symbol, String entrySide) {
        List<BinanceFuturesPositionRiskResponse> positions = binanceClient.getPositionRisk(symbol);
        if (positions == null || positions.isEmpty()) {
            return null;
        }
        for (BinanceFuturesPositionRiskResponse position : positions) {
            if (!symbol.equalsIgnoreCase(position.getSymbol())) {
                continue;
            }
            if ("BOTH".equalsIgnoreCase(position.getPositionSide())) {
                return position;
            }
        }
        for (BinanceFuturesPositionRiskResponse position : positions) {
            if (!symbol.equalsIgnoreCase(position.getSymbol())) {
                continue;
            }
            BigDecimal positionAmt = signedDecimal(position.getPositionAmt());
            if (positionAmt == null || positionAmt.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            if ("BUY".equalsIgnoreCase(entrySide) && positionAmt.compareTo(BigDecimal.ZERO) > 0) {
                return position;
            }
            if ("SELL".equalsIgnoreCase(entrySide) && positionAmt.compareTo(BigDecimal.ZERO) < 0) {
                return position;
            }
        }
        return positions.stream()
                .filter(position -> symbol.equalsIgnoreCase(position.getSymbol()))
                .findFirst()
                .orElse(null);
    }

    private BigDecimal positionRiskQuantity(BinanceFuturesPositionRiskResponse positionRisk) {
        if (positionRisk == null) {
            return null;
        }
        BigDecimal positionAmt = signedDecimal(positionRisk != null ? positionRisk.getPositionAmt() : null);
        if (positionAmt == null) {
            return null;
        }
        return positionAmt.abs();
    }

    private Map<String, Object> responseSummary(BinanceFuturesOrderResponse response) {
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
        summary.put("cumQuote", response.getCumQuote());
        summary.put("stopPrice", response.getStopPrice());
        summary.put("workingType", response.getWorkingType());
        summary.put("positionSide", response.getPositionSide());
        summary.put("closePosition", response.getClosePosition());
        summary.put("reduceOnly", response.getReduceOnly());
        summary.put("priceProtect", response.getPriceProtect());
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

    private Map<String, Object> positionSummary(BinanceFuturesPositionRiskResponse positionRisk) {
        if (positionRisk == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("symbol", positionRisk.getSymbol());
        summary.put("positionSide", positionRisk.getPositionSide());
        summary.put("positionAmt", positionRisk.getPositionAmt());
        summary.put("entryPrice", positionRisk.getEntryPrice());
        summary.put("breakEvenPrice", positionRisk.getBreakEvenPrice());
        summary.put("markPrice", positionRisk.getMarkPrice());
        summary.put("unRealizedProfit", positionRisk.getUnRealizedProfit());
        summary.put("notional", positionRisk.getNotional());
        summary.put("updateTime", positionRisk.getUpdateTime());
        return summary;
    }

    private boolean alignsToIncrement(BigDecimal value, BigDecimal increment) {
        if (value == null || increment == null || increment.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal normalized = value.divide(increment, 0, RoundingMode.DOWN).multiply(increment);
        return normalized.compareTo(value) == 0;
    }

    private BigDecimal nonZero(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return value;
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

    private BigDecimal positiveDecimal(String value) {
        BigDecimal decimal = signedDecimal(value);
        if (decimal == null || decimal.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return decimal;
    }

    private BigDecimal positiveOrZero(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? BigDecimal.ZERO : value;
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : value;
    }

    private void pauseBetweenFillLookups() {
        try {
            Thread.sleep(ENTRY_FILL_LOOKUP_DELAY_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private String normalizeType(String type) {
        return type == null ? "" : type.trim().toUpperCase();
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

    private record OrderPayloads(BinanceOrderFieldsDTO entry, BinanceOrderFieldsDTO sl, BinanceOrderFieldsDTO tp) {
    }

    private record RequestedExecutionSaveResult(LiveTradeExecution execution, boolean created) {
    }

    private record EntryFillResolution(String source,
            LiveTradeExecutionState executionState,
            BigDecimal filledQuantity,
            BigDecimal avgPrice,
            BigDecimal positionEntryPrice,
            String orderStatus,
            Map<String, Object> orderSummary,
            Map<String, Object> positionSummary) {
        private boolean hasPosition() {
            return filledQuantity != null && filledQuantity.compareTo(BigDecimal.ZERO) > 0;
        }

        private BigDecimal positionQuantity() {
            return filledQuantity == null ? BigDecimal.ZERO : filledQuantity;
        }

        private BigDecimal referencePrice() {
            if (avgPrice != null && avgPrice.compareTo(BigDecimal.ZERO) > 0) {
                return avgPrice;
            }
            if (positionEntryPrice != null && positionEntryPrice.compareTo(BigDecimal.ZERO) > 0) {
                return positionEntryPrice;
            }
            return null;
        }

        private Map<String, Object> entrySummary() {
            Map<String, Object> summary = new LinkedHashMap<>(orderSummary != null ? orderSummary : Map.of());
            summary.put("resolvedSource", source);
            summary.put("resolvedFilledQuantity", filledQuantity);
            summary.put("resolvedAvgPrice", avgPrice);
            summary.put("resolvedPositionEntryPrice", positionEntryPrice);
            summary.put("resolvedState", executionState.name());
            return summary;
        }
    }

    private record ProtectionValidation(boolean valid, String message, Map<String, Object> details) {
        private static ProtectionValidation passed() {
            return new ProtectionValidation(true, null, Map.of());
        }

        private static ProtectionValidation invalid(String message, Map<String, Object> details) {
            return new ProtectionValidation(false, message, details);
        }
    }

    private record ProtectionLegSubmission(String legKey,
            String legLabel,
            String clientAlgoId,
            Long algoId,
            boolean success,
            String errorCode,
            String errorMessage,
            Map<String, Object> details,
            Map<String, Object> summary) {
        private static ProtectionLegSubmission success(String legKey,
                String legLabel,
                String clientAlgoId,
                BinanceFuturesAlgoOrderResponse response) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("submitted", true);
            summary.put("leg", legKey);
            summary.put("response", new LinkedHashMap<>());
            summary.put("errorCode", null);
            summary.put("errorMessage", null);
            Map<String, Object> responseSummary = new LinkedHashMap<>();
            responseSummary.put("algoId", response.getAlgoId());
            responseSummary.put("clientAlgoId", response.getClientAlgoId());
            responseSummary.put("algoStatus", response.getAlgoStatus());
            responseSummary.put("orderType", response.getOrderType());
            responseSummary.put("triggerPrice", response.getTriggerPrice());
            responseSummary.put("workingType", response.getWorkingType());
            responseSummary.put("closePosition", response.getClosePosition());
            responseSummary.put("updateTime", response.getUpdateTime());
            summary.put("response", responseSummary);
            return new ProtectionLegSubmission(
                    legKey,
                    legLabel,
                    clientAlgoId,
                    response.getAlgoId(),
                    true,
                    null,
                    null,
                    Map.of(),
                    summary);
        }

        private static ProtectionLegSubmission failure(String legKey,
                String legLabel,
                String clientAlgoId,
                String errorCode,
                String errorMessage,
                Map<String, Object> details) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("submitted", false);
            summary.put("leg", legKey);
            summary.put("clientAlgoId", clientAlgoId);
            summary.put("errorCode", errorCode);
            summary.put("errorMessage", errorMessage);
            summary.put("details", details);
            return new ProtectionLegSubmission(
                    legKey,
                    legLabel,
                    clientAlgoId,
                    null,
                    false,
                    errorCode,
                    errorMessage,
                    details != null ? details : Map.of(),
                    summary);
        }
    }

    private record ProtectionFailureSummary(String errorCode,
            String errorMessage,
            String operatorMessage,
            boolean downsideProtected,
            boolean emergencyCloseRequired,
            List<String> failedLegs,
            Map<String, Object> details) {
        private Map<String, Object> summary() {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("errorCode", errorCode);
            summary.put("errorMessage", errorMessage);
            summary.put("downsideProtected", downsideProtected);
            summary.put("emergencyCloseRequired", emergencyCloseRequired);
            summary.put("failedLegs", failedLegs);
            summary.put("details", details);
            return summary;
        }
    }
}
