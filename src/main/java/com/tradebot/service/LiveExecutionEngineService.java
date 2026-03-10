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
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionEvent;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.entity.OrderFields;
import com.tradebot.entity.Recommendation;
import com.tradebot.repository.LiveTradeExecutionEventRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.security.LocalMutationGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
public class LiveExecutionEngineService {

    private static final String PROTECTION_ALGO_TYPE = "CONDITIONAL";

    private final RecommendationRepository recommendationRepository;
    private final LiveTradeExecutionRepository liveTradeExecutionRepository;
    private final LiveTradeExecutionEventRepository liveTradeExecutionEventRepository;
    private final LiveTradingPreflightService liveTradingPreflightService;
    private final LiveTradingBinanceDiagnosticsService liveTradingBinanceDiagnosticsService;
    private final LiveTradePersistenceService liveTradePersistenceService;
    private final LiveExecutionTransactionService liveExecutionTransactionService;
    private final BinanceClient binanceClient;
    private final ObjectMapper objectMapper;
    private final LiveTradingMapper liveTradingMapper;
    private final OrderStateSyncService orderStateSyncService;
    private final ExchangeExecutionPreflightService exchangeExecutionPreflightService;
    private final ClientOrderIdFactory clientOrderIdFactory;
    private final BinanceExecutionResponseParser responseParser;
    private final ExchangeRoundingService exchangeRoundingService;
    private final BudgetTargetSessionStreamPublisher sessionStreamPublisher;

    public LiveTradeExecutionDTO execute(ApprovedExecutionCommand command,
            LocalMutationGuard.LocalRequestCheck localRequestCheck) {
        UUID recommendationId = command.recommendationId();
        UUID idempotencyKey = command.idempotencyKey();

        LiveTradeExecution existingAttempt = liveTradeExecutionRepository
                .findFirstByRecommendation_IdAndClientRequestId(recommendationId, idempotencyKey)
                .orElse(null);
        if (existingAttempt != null) {
            appendEvent(existingAttempt, "DUPLICATE_REQUEST_IGNORED", existingAttempt.getExecutionState().name(),
                    "Live execution request reused an existing idempotency key.",
                    LiveTradingBlockerCodes.DUPLICATE_SUBMIT_BLOCKED,
                    payloadOf("traceId", command.traceId(), "clientRequestId", idempotencyKey));
            return toDetail(existingAttempt);
        }

        Recommendation recommendation = recommendationRepository.findDetailedById(recommendationId)
                .orElseThrow(() -> new NoSuchElementException("Recommendation not found: " + recommendationId));

        LiveExecutionTransactionService.PreparedExecution preparedExecution = liveExecutionTransactionService.prepareExecution(
                command,
                recommendation,
                writeJson(buildPayloadSnapshot(recommendation, command)));
        LiveTradeExecution execution = preparedExecution.execution();
        if (!preparedExecution.created()) {
            appendEvent(execution, "DUPLICATE_REQUEST_IGNORED", execution.getExecutionState().name(),
                    "Live execution request reused an existing idempotency key.",
                    LiveTradingBlockerCodes.DUPLICATE_SUBMIT_BLOCKED,
                    payloadOf("traceId", command.traceId(), "clientRequestId", idempotencyKey));
            return toDetail(execution);
        }

        LiveTradingPreflightDTO preflight = liveTradingPreflightService.evaluate(
                recommendationId,
                execution.getId(),
                localRequestCheck);
        ExchangeExecutionPreflightService.ExchangeExecutionPreflightResult exchangePreflight = exchangeExecutionPreflightService
                .evaluate(recommendation, preflight.getPlaceability());
        exchangeExecutionPreflightService.applyTo(preflight, exchangePreflight);
        if (!exchangePreflight.valid()) {
            preflight.getBlockedReasons().add(new LiveTradeBlockedReasonDTO(
                    LiveTradingBlockerCodes.EXCHANGE_FILTER_INVALID,
                    exchangePreflight.failures().getFirst(),
                    "exchangeValidation",
                    exchangePreflight.details()));
            preflight.setExecutable(false);
            preflight.setAllowed(false);
            preflight.getSummary().setExecutableNow(false);
            preflight.getSummary().setPrimaryBlockerCode(LiveTradingBlockerCodes.EXCHANGE_FILTER_INVALID);
            preflight.getSummary().setPrimaryBlockerMessage(exchangePreflight.failures().getFirst());
        }
        execution.setPreflightJson(writeJson(preflight));
        execution.setReservedMarginUsdt(resolveReservedMarginUsdt(preflight));
        execution.setUpdatedAt(Instant.now());
        execution = liveTradeExecutionRepository.save(execution);

        if (!preflight.isExecutable()) {
            return completeRejectedExecution(execution, preflight);
        }

        ExchangeExecutionPreflightService.OrderPayloads orderPayloads = exchangePreflight.roundedPayloads();
        if (orderPayloads == null) {
            return rejectExecution(execution,
                    LiveTradingBlockerCodes.MISSING_ORDER_FIELDS,
                    "Rounded exchange payloads are unavailable.",
                    Map.of("exchangePreflight", exchangePreflight.details()));
        }

        try {
            execution = liveExecutionTransactionService.reserveEntrySubmission(
                    execution.getId(),
                    command,
                    resolveReservedMarginUsdt(preflight),
                    orderPayloads.entry().getQuantity(),
                    clientOrderIdFactory.entryOrderId(execution),
                    clientOrderIdFactory.stopLossOrderId(execution),
                    clientOrderIdFactory.takeProfitOrderId(execution),
                    clientOrderIdFactory.emergencyCloseOrderId(execution));
        } catch (LiveExecutionTransactionService.LiveExecutionPreparationException ex) {
            return rejectExecution(execution,
                    ex.code(),
                    ex.getMessage(),
                    ex.details());
        }

        Map<String, Object> exchangeResponses = new LinkedHashMap<>();
        try {
            exchangeResponses.put("positionMode", binanceClient.ensureOneWayPositionMode());
            exchangeResponses.put("marginMode", binanceClient.ensureIsolatedMargin(recommendation.getSymbol()));
            exchangeResponses.put("leverage",
                    binanceClient.setLeverage(recommendation.getSymbol(), safeLeverageRecommendation(recommendation)));

            BinanceFuturesOrderResponse entryResponse = binanceClient.submitOrder(
                    buildEntryOrderParams(orderPayloads.entry(), execution.getEntryClientOrderId()));
            execution.setEntryOrderId(entryResponse.getOrderId());
            exchangeResponses.put("entry", responseParser.orderSummary(entryResponse));
            execution.setEntryResponseJson(writeJson(payloadOf(
                    "entry", responseParser.orderSummary(entryResponse),
                    "positionMode", exchangeResponses.get("positionMode"),
                    "marginMode", exchangeResponses.get("marginMode"),
                    "leverage", exchangeResponses.get("leverage"))));
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            execution.setExecutionState(LiveTradeExecutionState.ENTRY_SUBMITTED);
            execution.setUpdatedAt(Instant.now());
            liveTradeExecutionRepository.save(execution);
            liveTradePersistenceService.upsertOrder(
                    execution,
                    "ENTRY",
                    execution.getEntryClientOrderId(),
                    execution.getEntryOrderId(),
                    null,
                    null,
                    execution.getRequestedQty(),
                    null,
                    null,
                    null,
                    null,
                    execution.getExecutionState().name(),
                    buildEntryOrderParams(orderPayloads.entry(), execution.getEntryClientOrderId()),
                    responseParser.orderSummary(entryResponse),
                    exchangeResponses);
            appendEvent(execution, "ENTRY_SUBMITTED", execution.getExecutionState().name(),
                    "Entry order submitted to Binance.",
                    null,
                    responseParser.orderSummary(entryResponse));

            OrderStateSyncService.EntryFillResolution entryFill = orderStateSyncService.resolveEntryFill(execution, orderPayloads.entry(), entryResponse);
            exchangeResponses.put("entry", entryFill.entrySummary());
            exchangeResponses.put("position", entryFill.positionSummary());
            execution.setExecutionState(entryFill.hasPosition() ? LiveTradeExecutionState.ENTRY_FILLED : LiveTradeExecutionState.RECONCILING);
            execution.setActualFilledQty(entryFill.positionQuantity());
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            execution.setEntryResponseJson(writeJson(payloadOf(
                    "entry", entryFill.entrySummary(),
                    "position", entryFill.positionSummary())));
            execution.setUpdatedAt(Instant.now());
            if (!entryFill.hasPosition()) {
                execution.setErrorCode(LiveTradingBlockerCodes.ENTRY_FILL_UNRESOLVED);
                execution.setErrorMessage("Entry was accepted but Binance fill/position truth is not yet usable for protection.");
                execution.setErrorDetailsJson(writeJson(Map.of(
                        "code", LiveTradingBlockerCodes.ENTRY_FILL_UNRESOLVED,
                        "entry", entryFill.entrySummary(),
                        "position", entryFill.positionSummary())));
            }
            liveTradeExecutionRepository.save(execution);
            liveTradePersistenceService.upsertOrder(
                    execution,
                    "ENTRY",
                    execution.getEntryClientOrderId(),
                    execution.getEntryOrderId(),
                    null,
                    null,
                    execution.getRequestedQty(),
                    entryFill.positionQuantity(),
                    null,
                    null,
                    entryFill.avgPrice(),
                    execution.getExecutionState().name(),
                    buildEntryOrderParams(orderPayloads.entry(), execution.getEntryClientOrderId()),
                    entryFill.entrySummary(),
                    exchangeResponses);
            refreshSessionRollup(execution);
            appendEvent(execution, execution.getExecutionState().name(), execution.getExecutionState().name(),
                    entryFill.hasPosition()
                            ? "Entry fill detected from Binance truth."
                            : "Entry accepted, but fill state is unresolved. Protection submission was deferred.",
                    execution.getErrorCode(),
                    payloadOf(
                            "source", entryFill.source(),
                            "filledQuantity", entryFill.filledQuantity(),
                            "avgPrice", entryFill.avgPrice(),
                            "positionQuantity", entryFill.positionQuantity(),
                            "positionEntryPrice", entryFill.positionEntryPrice(),
                            "orderStatus", entryFill.orderStatus()));

            if (!entryFill.hasPosition()) {
                return orderStateSyncService.reconcileExecution(execution.getId(), execution.getOperatorId(), command.traceId(), false);
            }

            execution.setExecutionState(LiveTradeExecutionState.PROTECTION_SUBMITTING);
            execution.setUpdatedAt(Instant.now());
            liveTradeExecutionRepository.save(execution);
            appendEvent(execution, "PROTECTION_SUBMITTING", execution.getExecutionState().name(),
                    "Submitting protection orders to Binance.",
                    null,
                    payloadOf("traceId", command.traceId()));

            BinanceExchangeInfoResponse.SymbolInfo symbolInfo = exchangePreflight.symbolInfo();
            ProtectionLegSubmission stopLoss = submitProtectionLeg(
                    "stopLoss",
                    "STOP_LOSS",
                    orderPayloads.entry(),
                    orderPayloads.sl(),
                    execution.getSlClientOrderId(),
                    symbolInfo,
                    entryFill);
            exchangeResponses.put("stopLoss", stopLoss.summary());
            persistProtectionLegEvent(execution, stopLoss, orderPayloads.sl(), exchangeResponses);

            ProtectionLegSubmission takeProfit = submitProtectionLeg(
                    "takeProfit",
                    "TAKE_PROFIT",
                    orderPayloads.entry(),
                    orderPayloads.tp(),
                    execution.getTpClientOrderId(),
                    symbolInfo,
                    entryFill);
            exchangeResponses.put("takeProfit", takeProfit.summary());
            persistProtectionLegEvent(execution, takeProfit, orderPayloads.tp(), exchangeResponses);

            execution.setSlOrderId(stopLoss.algoId());
            execution.setTpOrderId(takeProfit.algoId());
            exchangeResponses.put("position", entryFill.positionSummary());
            execution.setProtectionResponseJson(writeJson(payloadOf(
                    "stopLoss", stopLoss.summary(),
                    "takeProfit", takeProfit.summary(),
                    "position", entryFill.positionSummary())));

            if (stopLoss.success() && takeProfit.success()) {
                execution.setExecutionState(LiveTradeExecutionState.PROTECTION_ACTIVE);
                execution.setExchangeResponseJson(writeJson(exchangeResponses));
                execution.setUpdatedAt(Instant.now());
                liveTradeExecutionRepository.save(execution);
                refreshSessionRollup(execution);
                appendEvent(execution, "PROTECTION_ACTIVE", execution.getExecutionState().name(),
                        "Both protection legs were accepted by Binance.",
                        null,
                        payloadOf(
                                "filledQuantity", entryFill.filledQuantity(),
                                "avgPrice", entryFill.avgPrice(),
                                "positionQuantity", entryFill.positionQuantity()));
                return orderStateSyncService.reconcileExecution(execution.getId(), execution.getOperatorId(), command.traceId(), false);
            }

            ProtectionFailureSummary failure = summarizeProtectionFailure(stopLoss, takeProfit);
            exchangeResponses.put("protectionFailure", failure.summary());
            execution.setRequiresIntervention(true);
            execution.setCriticalIssueJson(writeJson(failure.criticalIssue()));
            execution.setErrorCode(failure.errorCode());
            execution.setErrorMessage(failure.errorMessage());
            execution.setErrorDetailsJson(writeJson(failure.summary()));
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            execution.setProtectionResponseJson(writeJson(payloadOf(
                    "stopLoss", stopLoss.summary(),
                    "takeProfit", takeProfit.summary(),
                    "protectionFailure", failure.summary())));

            if (failure.downsideProtected()) {
                execution.setExecutionState(LiveTradeExecutionState.ACTIVE);
                execution.setUpdatedAt(Instant.now());
                liveTradeExecutionRepository.save(execution);
                refreshSessionRollup(execution);
                appendEvent(execution, "ACTIVE", execution.getExecutionState().name(),
                        failure.operatorMessage(),
                        failure.errorCode(),
                        failure.summary());
                return orderStateSyncService.reconcileExecution(execution.getId(), execution.getOperatorId(), command.traceId(), false);
            }

            BigDecimal emergencyCloseQuantity = normalizeEmergencyCloseQuantity(entryFill.positionQuantity(), symbolInfo);
            if (emergencyCloseQuantity == null || emergencyCloseQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                execution.setExecutionState(LiveTradeExecutionState.RECONCILING);
                execution.setUpdatedAt(Instant.now());
                liveTradeExecutionRepository.save(execution);
                refreshSessionRollup(execution);
                appendEvent(execution, "RECONCILING", execution.getExecutionState().name(),
                        "Protection failed and Binance position size could not be normalized for emergency close.",
                        failure.errorCode(),
                        payloadOf(
                                "positionQuantity", entryFill.positionQuantity(),
                                "rawPosition", entryFill.positionSummary(),
                                "criticalIssue", failure.criticalIssue()));
                return orderStateSyncService.reconcileExecution(execution.getId(), execution.getOperatorId(), command.traceId(), false);
            }

            try {
                BinanceFuturesOrderResponse emergencyClose = binanceClient.submitOrder(
                        buildEmergencyCloseParams(orderPayloads.entry(),
                                execution.getEmergencyCloseClientOrderId(),
                                emergencyCloseQuantity));
                execution.setEmergencyCloseOrderId(emergencyClose.getOrderId());
                execution.setExecutionState(LiveTradeExecutionState.CLOSING);
                exchangeResponses.put("emergencyClose", responseParser.orderSummary(emergencyClose));
                execution.setExchangeResponseJson(writeJson(exchangeResponses));
                execution.setProtectionResponseJson(writeJson(payloadOf(
                        "emergencyClose", responseParser.orderSummary(emergencyClose),
                        "protectionFailure", failure.summary())));
                execution.setUpdatedAt(Instant.now());
                liveTradeExecutionRepository.save(execution);
                liveTradePersistenceService.upsertOrder(
                        execution,
                        "EMERGENCY_CLOSE",
                        execution.getEmergencyCloseClientOrderId(),
                        execution.getEmergencyCloseOrderId(),
                        null,
                        null,
                        emergencyCloseQuantity,
                        null,
                        null,
                        null,
                        null,
                        execution.getExecutionState().name(),
                        buildEmergencyCloseParams(orderPayloads.entry(),
                                execution.getEmergencyCloseClientOrderId(),
                                emergencyCloseQuantity),
                        responseParser.orderSummary(emergencyClose),
                        exchangeResponses);
                refreshSessionRollup(execution);
                appendEvent(execution, "CLOSING", execution.getExecutionState().name(),
                        "Emergency reduce-only close submitted after stop-loss protection failure.",
                        null,
                        payloadOf(
                                "positionQuantity", emergencyCloseQuantity,
                                "response", responseParser.orderSummary(emergencyClose),
                                "criticalIssue", failure.criticalIssue()));
                return orderStateSyncService.reconcileExecution(execution.getId(), execution.getOperatorId(), command.traceId(), false);
            } catch (Exception emergencyCloseFailure) {
                LiveTradeBlockedReasonDTO emergencyFailure = liveTradingBinanceDiagnosticsService
                        .classifyExecutionFailure(emergencyCloseFailure);
                execution.setExecutionState(LiveTradeExecutionState.RECONCILING);
                execution.setErrorCode(emergencyFailure.getCode());
                execution.setErrorMessage("Protection failed and emergency close also failed: " + emergencyFailure.getMessage());
                execution.setErrorDetailsJson(writeJson(emergencyFailure.getDetails()));
                execution.setUpdatedAt(Instant.now());
                liveTradeExecutionRepository.save(execution);
                refreshSessionRollup(execution);
                appendEvent(execution, "RECONCILING", execution.getExecutionState().name(),
                        "Emergency close failed after stop-loss protection failure.",
                        execution.getErrorCode(),
                        payloadOf("message", emergencyFailure.getMessage(), "details", emergencyFailure.getDetails()));
                return toDetail(execution);
            }
        } catch (WebClientRequestException ex) {
            execution.setExecutionState(LiveTradeExecutionState.RECONCILING);
            execution.setErrorCode(LiveTradingBlockerCodes.UPSTREAM_TIMEOUT);
            execution.setErrorMessage("Submission timed out or lost connectivity. Reconciliation required.");
            execution.setErrorDetailsJson(writeJson(Map.of("message", ex.getMessage())));
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            execution.setUpdatedAt(Instant.now());
            liveTradeExecutionRepository.save(execution);
            refreshSessionRollup(execution);
            appendEvent(execution, "RECONCILING", execution.getExecutionState().name(),
                    "Submission outcome is unknown due to network timeout. Reconciliation required.",
                    execution.getErrorCode(),
                    payloadOf("message", ex.getMessage()));
            return toDetail(execution);
        } catch (WebClientResponseException ex) {
            LiveTradeBlockedReasonDTO failure = liveTradingBinanceDiagnosticsService.classifyExecutionFailure(ex);
            execution.setExecutionState(LiveTradeExecutionState.FAILED);
            execution.setErrorCode(failure.getCode());
            execution.setErrorMessage(failure.getMessage());
            execution.setErrorDetailsJson(writeJson(failure.getDetails()));
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            execution.setCompletedAt(Instant.now());
            execution.setUpdatedAt(Instant.now());
            liveTradeExecutionRepository.save(execution);
            refreshSessionRollup(execution);
            appendEvent(execution, "FAILED", execution.getExecutionState().name(),
                    "Binance rejected the live execution request.",
                    execution.getErrorCode(),
                    payloadOf(
                            "status", ex.getRawStatusCode(),
                            "message", failure.getMessage(),
                            "details", failure.getDetails()));
            return toDetail(execution);
        } catch (Exception ex) {
            LiveTradeBlockedReasonDTO failure = liveTradingBinanceDiagnosticsService.classifyExecutionFailure(ex);
            execution.setExecutionState(LiveTradeExecutionState.FAILED);
            execution.setErrorCode(failure.getCode());
            execution.setErrorMessage(failure.getMessage());
            execution.setErrorDetailsJson(writeJson(failure.getDetails()));
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            execution.setCompletedAt(Instant.now());
            execution.setUpdatedAt(Instant.now());
            liveTradeExecutionRepository.save(execution);
            refreshSessionRollup(execution);
            appendEvent(execution, "FAILED", execution.getExecutionState().name(),
                    "Live execution failed before Binance confirmation.",
                    execution.getErrorCode(),
                    payloadOf("message", failure.getMessage(), "details", failure.getDetails()));
            return toDetail(execution);
        }
    }

    public SafeCloseAttemptResult requestSafeClose(UUID executionId,
            String operatorId,
            String traceId,
            String closeReason) {
        LiveTradeExecution execution = liveTradeExecutionRepository.findById(executionId)
                .orElseThrow(() -> new NoSuchElementException("Live execution not found: " + executionId));

        if (!execution.getExecutionState().isActive()) {
            appendEvent(execution, "SAFE_CLOSE_SKIPPED", execution.getExecutionState().name(),
                    "Safe close request ignored because execution is not active.",
                    null,
                    payloadOf("traceId", traceId, "closeReason", closeReason));
            return new SafeCloseAttemptResult(
                    execution.getId(),
                    execution.getExecutionState() == LiveTradeExecutionState.CLOSED
                            ? SafeCloseAttemptResult.SafeCloseAttemptStatus.RESOLVED_FLAT
                            : SafeCloseAttemptResult.SafeCloseAttemptStatus.FAILED,
                    toDetail(execution),
                    execution.getErrorCode(),
                    execution.getErrorMessage(),
                    Map.of("closeReason", closeReason));
        }

        Map<String, Object> exchangeResponses = readJson(execution.getExchangeResponseJson());
        try {
            cancelProtectionIfPresent(execution, true, exchangeResponses);
            cancelProtectionIfPresent(execution, false, exchangeResponses);

            BinanceFuturesPositionRiskResponse positionRisk = orderStateSyncService.lookupPosition(execution.getSymbol(), execution.getSide());
            BinanceExchangeInfoResponse.SymbolInfo symbolInfo = binanceClient.getSymbolInfo(execution.getSymbol()).orElse(null);
            BigDecimal closeQuantity = normalizeEmergencyCloseQuantity(orderStateSyncService.positionRiskQuantity(positionRisk), symbolInfo);

            execution.setCloseReason(closeReason);
            execution.setUpdatedAt(Instant.now());

            if (closeQuantity == null || closeQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                exchangeResponses.put("safeClose", payloadOf(
                        "requested", true,
                        "submitted", false,
                        "reason", "NO_OPEN_POSITION",
                        "closeReason", closeReason));
                execution.setExchangeResponseJson(writeJson(exchangeResponses));
                liveTradeExecutionRepository.save(execution);
                refreshSessionRollup(execution);
                appendEvent(execution, "SAFE_CLOSE_SKIPPED", execution.getExecutionState().name(),
                        "Safe close found no open Binance position for this execution.",
                        null,
                        payloadOf("traceId", traceId, "closeReason", closeReason));
                LiveTradeExecutionDTO reconciled = orderStateSyncService.reconcileExecution(execution.getId(),
                        normalizeOperatorId(operatorId),
                        traceId,
                        false);
                return safeCloseResult(
                        execution.getId(),
                        "CLOSED".equalsIgnoreCase(reconciled.getExecutionState())
                                ? SafeCloseAttemptResult.SafeCloseAttemptStatus.RESOLVED_FLAT
                                : SafeCloseAttemptResult.SafeCloseAttemptStatus.NO_POSITION,
                        reconciled,
                        null,
                        null,
                        Map.of("closeReason", closeReason, "reason", "NO_OPEN_POSITION"));
            }

            if ((execution.getEmergencyCloseClientOrderId() != null || execution.getEmergencyCloseOrderId() != null)
                    && (execution.getExecutionState() == LiveTradeExecutionState.CLOSING
                    || execution.getExecutionState() == LiveTradeExecutionState.RECONCILING)) {
                appendEvent(execution, "SAFE_CLOSE_ALREADY_SUBMITTED", execution.getExecutionState().name(),
                        "Safe close request reused an existing emergency close order submission.",
                        null,
                        payloadOf("traceId", traceId, "closeReason", closeReason));
                LiveTradeExecutionDTO reconciled = orderStateSyncService.reconcileExecution(execution.getId(),
                        normalizeOperatorId(operatorId),
                        traceId,
                        false);
                return safeCloseResult(
                        execution.getId(),
                        "CLOSED".equalsIgnoreCase(reconciled.getExecutionState())
                                ? SafeCloseAttemptResult.SafeCloseAttemptStatus.RESOLVED_FLAT
                                : SafeCloseAttemptResult.SafeCloseAttemptStatus.ALREADY_SUBMITTED,
                        reconciled,
                        null,
                        null,
                        Map.of("closeReason", closeReason));
            }

            BinanceOrderFieldsDTO entryOrder = readEntryOrder(execution.getRecommendation().getOrderFields());
            if (entryOrder == null) {
                entryOrder = new BinanceOrderFieldsDTO();
                entryOrder.setSymbol(execution.getSymbol());
                entryOrder.setSide(execution.getSide());
            }

            if (execution.getEmergencyCloseClientOrderId() == null || execution.getEmergencyCloseClientOrderId().isBlank()) {
                execution.setEmergencyCloseClientOrderId(clientOrderIdFactory.emergencyCloseOrderId(execution));
            }
            Map<String, String> emergencyCloseParams = buildEmergencyCloseParams(
                    entryOrder,
                    execution.getEmergencyCloseClientOrderId(),
                    closeQuantity);
            BinanceFuturesOrderResponse emergencyClose = binanceClient.submitOrder(emergencyCloseParams);
            execution.setEmergencyCloseOrderId(emergencyClose.getOrderId());
            execution.setExecutionState(LiveTradeExecutionState.CLOSING);
            exchangeResponses.put("safeClose", payloadOf(
                    "requested", true,
                    "submitted", true,
                    "closeReason", closeReason,
                    "quantity", closeQuantity));
            exchangeResponses.put("emergencyClose", responseParser.orderSummary(emergencyClose));
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            execution.setUpdatedAt(Instant.now());
            liveTradeExecutionRepository.save(execution);
            liveTradePersistenceService.upsertOrder(
                    execution,
                    "EMERGENCY_CLOSE",
                    execution.getEmergencyCloseClientOrderId(),
                    execution.getEmergencyCloseOrderId(),
                    null,
                    null,
                    closeQuantity,
                    null,
                    null,
                    null,
                    null,
                    execution.getExecutionState().name(),
                    emergencyCloseParams,
                    responseParser.orderSummary(emergencyClose),
                    exchangeResponses);
            refreshSessionRollup(execution);
            appendEvent(execution, "SAFE_CLOSE_SUBMITTED", execution.getExecutionState().name(),
                    "Safe reduce-only close submitted for an active execution.",
                    null,
                    payloadOf(
                            "traceId", traceId,
                            "closeReason", closeReason,
                            "quantity", closeQuantity,
                            "response", responseParser.orderSummary(emergencyClose)));
            LiveTradeExecutionDTO reconciled = orderStateSyncService.reconcileExecution(execution.getId(),
                    normalizeOperatorId(operatorId),
                    traceId,
                    false);
            return safeCloseResult(
                    execution.getId(),
                    "CLOSED".equalsIgnoreCase(reconciled.getExecutionState())
                            ? SafeCloseAttemptResult.SafeCloseAttemptStatus.RESOLVED_FLAT
                            : SafeCloseAttemptResult.SafeCloseAttemptStatus.SUBMITTED,
                    reconciled,
                    null,
                    null,
                    Map.of("closeReason", closeReason, "quantity", closeQuantity));
        } catch (WebClientRequestException ex) {
            execution.setExecutionState(LiveTradeExecutionState.CLOSING);
            execution.setErrorCode(LiveTradingBlockerCodes.UPSTREAM_TIMEOUT);
            execution.setErrorMessage("Safe close submission timed out. Reconciliation required.");
            execution.setErrorDetailsJson(writeJson(Map.of("message", ex.getMessage())));
            execution.setCloseReason(closeReason);
            execution.setUpdatedAt(Instant.now());
            exchangeResponses.put("safeClose", payloadOf(
                    "requested", true,
                    "submitted", false,
                    "unknown", true,
                    "reason", "TIMEOUT",
                    "closeReason", closeReason));
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            liveTradeExecutionRepository.save(execution);
            refreshSessionRollup(execution);
            appendEvent(execution, "SAFE_CLOSE_TIMEOUT", execution.getExecutionState().name(),
                    "Safe close timed out before Binance confirmation. Reconciliation required.",
                    execution.getErrorCode(),
                    payloadOf("traceId", traceId, "closeReason", closeReason, "message", ex.getMessage()));
            LiveTradeExecutionDTO reconciled = orderStateSyncService.reconcileExecution(execution.getId(),
                    normalizeOperatorId(operatorId),
                    traceId,
                    false);
            return safeCloseResult(
                    execution.getId(),
                    "CLOSED".equalsIgnoreCase(reconciled.getExecutionState())
                            ? SafeCloseAttemptResult.SafeCloseAttemptStatus.RESOLVED_FLAT
                            : SafeCloseAttemptResult.SafeCloseAttemptStatus.TIMED_OUT,
                    reconciled,
                    execution.getErrorCode(),
                    execution.getErrorMessage(),
                    Map.of("closeReason", closeReason, "message", ex.getMessage()));
        } catch (Exception ex) {
            LiveTradeBlockedReasonDTO failure = liveTradingBinanceDiagnosticsService.classifyExecutionFailure(ex);
            execution.setErrorCode(failure.getCode());
            execution.setErrorMessage(failure.getMessage());
            execution.setErrorDetailsJson(writeJson(failure.getDetails()));
            execution.setCloseReason(closeReason);
            execution.setUpdatedAt(Instant.now());
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            liveTradeExecutionRepository.save(execution);
            refreshSessionRollup(execution);
            appendEvent(execution, "SAFE_CLOSE_FAILED", execution.getExecutionState().name(),
                    "Safe close failed before Binance confirmation.",
                    failure.getCode(),
                    payloadOf("traceId", traceId, "closeReason", closeReason, "details", failure.getDetails()));
            LiveTradeExecutionDTO detail = toDetail(execution);
            return safeCloseResult(
                    execution.getId(),
                    SafeCloseAttemptResult.SafeCloseAttemptStatus.FAILED,
                    detail,
                    failure.getCode(),
                    failure.getMessage(),
                    failure.getDetails());
        }
    }

    private LiveTradeExecutionDTO completeRejectedExecution(LiveTradeExecution execution, LiveTradingPreflightDTO preflight) {
        execution.setExecutionState(LiveTradeExecutionState.PREFLIGHT_REJECTED);
        execution.setErrorCode(firstBlockedCode(preflight.getBlockedReasons()));
        execution.setErrorMessage(firstBlockedMessage(preflight.getBlockedReasons()));
        execution.setErrorDetailsJson(writeJson(Map.of("blockedReasons", preflight.getBlockedReasons())));
        execution.setCompletedAt(Instant.now());
        execution.setUpdatedAt(Instant.now());
        liveTradeExecutionRepository.save(execution);
        refreshSessionRollup(execution);
        appendEvent(execution, "PREFLIGHT_REJECTED", execution.getExecutionState().name(),
                "Preflight blocked live execution.",
                execution.getErrorCode(),
                payloadOf("blockedReasons", preflight.getBlockedReasons()));
        return toDetail(execution);
    }

    private LiveTradeExecutionDTO rejectExecution(LiveTradeExecution execution,
            String code,
            String message,
            Map<String, Object> details) {
        execution.setExecutionState(LiveTradeExecutionState.PREFLIGHT_REJECTED);
        execution.setErrorCode(code);
        execution.setErrorMessage(message);
        execution.setErrorDetailsJson(writeJson(details));
        execution.setCompletedAt(Instant.now());
        execution.setUpdatedAt(Instant.now());
        liveTradeExecutionRepository.save(execution);
        appendEvent(execution, "PREFLIGHT_REJECTED", execution.getExecutionState().name(), message, code, details);
        return toDetail(execution);
    }

    private Map<String, Object> buildPayloadSnapshot(Recommendation recommendation,
            ApprovedExecutionCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recommendationId", recommendation.getId());
        payload.put("scanRunId", recommendation.getScanRun() != null ? recommendation.getScanRun().getId() : null);
        payload.put("symbol", recommendation.getSymbol());
        payload.put("side", recommendation.getSide());
        payload.put("request", payloadOf(
                "clientRequestId", command.idempotencyKey(),
                "operatorNote", command.operatorNote()));
        payload.put("traceId", command.traceId());
        payload.put("allocatedBudgetSliceUsdt", command.allocatedBudgetSliceUsdt());
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
            OrderStateSyncService.EntryFillResolution entryFill) {
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
            return ProtectionLegSubmission.success(legKey, legLabel, clientAlgoId, response, responseParser.algoSummary(response));
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

    private ProtectionValidation validateProtectionOrder(String legKey,
            BinanceOrderFieldsDTO entryOrder,
            BinanceOrderFieldsDTO protectionOrder,
            BinanceExchangeInfoResponse.SymbolInfo symbolInfo,
            OrderStateSyncService.EntryFillResolution entryFill) {
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
        BigDecimal tickSize = symbolInfo != null && symbolInfo.getTickSize() != null && symbolInfo.getTickSize().compareTo(BigDecimal.ZERO) > 0
                ? symbolInfo.getTickSize()
                : null;
        if (tickSize != null && !exchangeRoundingService.alignsToIncrement(protectionOrder.getStopPrice(), tickSize)) {
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

    private void persistProtectionLegEvent(LiveTradeExecution execution,
            ProtectionLegSubmission submission,
            BinanceOrderFieldsDTO protectionOrder,
            Map<String, Object> snapshotPayload) {
        liveTradePersistenceService.upsertOrder(
                execution,
                "stopLoss".equals(submission.legKey()) ? "STOP_LOSS" : "TAKE_PROFIT",
                null,
                null,
                submission.clientAlgoId(),
                submission.algoId(),
                execution.getActualFilledQty(),
                null,
                null,
                protectionOrder != null ? protectionOrder.getStopPrice() : null,
                null,
                submission.success() ? "SUBMITTED" : "FAILED",
                protectionOrder == null ? Map.of() : buildProtectionOrderParams(protectionOrder, submission.clientAlgoId()),
                submission.summary(),
                snapshotPayload);
        if (submission.success()) {
            appendEvent(execution,
                    submission.legKey().equals("stopLoss") ? "STOP_LOSS_SUBMITTED" : "TAKE_PROFIT_SUBMITTED",
                    LiveTradeExecutionState.PROTECTION_SUBMITTING.name(),
                    submission.legLabel() + " protection order submitted to Binance.",
                    null,
                    submission.summary());
            return;
        }
        appendEvent(execution,
                submission.legKey().equals("stopLoss") ? "STOP_LOSS_FAILED" : "TAKE_PROFIT_FAILED",
                LiveTradeExecutionState.PROTECTION_SUBMITTING.name(),
                submission.legLabel() + " protection order failed before active protection was confirmed.",
                submission.errorCode(),
                submission.summary());
    }

    private ProtectionFailureSummary summarizeProtectionFailure(ProtectionLegSubmission stopLoss,
            ProtectionLegSubmission takeProfit) {
        boolean downsideProtected = stopLoss.success();
        if (!stopLoss.success() && !takeProfit.success()) {
            Map<String, Object> summary = payloadOf("stopLoss", stopLoss.summary(), "takeProfit", takeProfit.summary());
            return new ProtectionFailureSummary(
                    stopLoss.errorCode() != null ? stopLoss.errorCode() : takeProfit.errorCode(),
                    "Stop-loss and take-profit protection both failed.",
                    "Protection failed: both stop-loss and take-profit orders were rejected or invalid.",
                    downsideProtected,
                    summary,
                    criticalIssue("PROTECTION_MISSING",
                            "Stop-loss and take-profit protection both failed.",
                            summary));
        }
        if (!stopLoss.success()) {
            Map<String, Object> summary = payloadOf("stopLoss", stopLoss.summary(), "takeProfit", takeProfit.summary());
            return new ProtectionFailureSummary(
                    stopLoss.errorCode(),
                    "Stop-loss protection failed.",
                    "Protection failed: stop-loss order was not active, so emergency close is required.",
                    false,
                    summary,
                    criticalIssue("STOP_LOSS_MISSING",
                            "Stop-loss protection failed and emergency close is required.",
                            summary));
        }
        Map<String, Object> summary = payloadOf("stopLoss", stopLoss.summary(), "takeProfit", takeProfit.summary());
        return new ProtectionFailureSummary(
                takeProfit.errorCode(),
                "Take-profit protection failed while stop-loss remains active.",
                "Take-profit protection failed, but stop-loss remains active.",
                true,
                summary,
                criticalIssue("TAKE_PROFIT_MISSING",
                        "Take-profit protection failed while stop-loss remains active.",
                        summary));
    }

    private Map<String, Object> criticalIssue(String code, String message, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("details", details == null ? Map.of() : details);
        payload.put("raisedAt", Instant.now().toString());
        return payload;
    }

    private BigDecimal normalizeEmergencyCloseQuantity(BigDecimal quantity,
            BinanceExchangeInfoResponse.SymbolInfo symbolInfo) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal stepSize = symbolInfo != null ? symbolInfo.getMarketStepSize() : null;
        if (stepSize == null && symbolInfo != null) {
            stepSize = symbolInfo.getStepSize();
        }
        return exchangeRoundingService.roundQuantityDown(quantity, stepSize);
    }

    private void cancelProtectionIfPresent(LiveTradeExecution execution,
            boolean stopLoss,
            Map<String, Object> exchangeResponses) {
        String clientAlgoId = stopLoss ? execution.getSlClientOrderId() : execution.getTpClientOrderId();
        Long algoId = stopLoss ? execution.getSlOrderId() : execution.getTpOrderId();
        if ((clientAlgoId == null || clientAlgoId.isBlank()) && algoId == null) {
            return;
        }
        try {
            Object response = binanceClient.cancelAlgoOrder(clientAlgoId, algoId);
            exchangeResponses.put(stopLoss ? "stopLossCancel" : "takeProfitCancel", response);
        } catch (WebClientResponseException ex) {
            if (ex.getRawStatusCode() == 400 || ex.getRawStatusCode() == 404) {
                exchangeResponses.put(stopLoss ? "stopLossCancel" : "takeProfitCancel", payloadOf(
                        "ignored", true,
                        "status", ex.getRawStatusCode(),
                        "message", ex.getMessage()));
                return;
            }
            throw ex;
        }
    }

    private BigDecimal resolveReservedMarginUsdt(LiveTradingPreflightDTO preflight) {
        if (preflight == null || preflight.getExchangeValidation() == null) {
            return null;
        }
        BigDecimal notional = toBigDecimal(preflight.getExchangeValidation().getEntryNotionalUsdt());
        Integer leverage = preflight.getExchangeValidation().getLeverage();
        if (notional == null || leverage == null || leverage <= 0) {
            return null;
        }
        return notional.divide(BigDecimal.valueOf(leverage), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private BinanceOrderFieldsDTO readEntryOrder(OrderFields orderFields) {
        if (orderFields == null || orderFields.getEntryOrderJson() == null || orderFields.getEntryOrderJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(orderFields.getEntryOrderJson(), BinanceOrderFieldsDTO.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private int safeLeverageRecommendation(Recommendation recommendation) {
        OrderFields orderFields = recommendation.getOrderFields();
        Integer leverage = orderFields != null ? orderFields.getLeverageRecommendation() : null;
        return leverage != null && leverage > 0 ? leverage : 1;
    }

    private String normalizeType(String type) {
        return type == null ? "" : type.trim().toUpperCase();
    }

    private String oppositeSide(String side) {
        return "BUY".equalsIgnoreCase(side) ? "SELL" : "BUY";
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
        Map<String, Object> snapshot = BudgetTargetAuditSupport.executionSnapshot(execution);
        LiveTradeExecutionEvent event = new LiveTradeExecutionEvent();
        event.setExecution(execution);
        event.setEventType(eventType);
        event.setEventStatus(eventStatus);
        event.setNotes(message);
        event.setErrorCode(errorCode);
        event.setTraceId(execution.getTraceId());
        event.setEventCategory(BudgetTargetAuditSupport.categoryForExecutionEvent(eventType));
        event.setSeverity(BudgetTargetAuditSupport.severityForExecutionEvent(eventType));
        event.setActor(BudgetTargetAuditSupport.normalizeActor(execution.getOperatorId()));
        event.setBeforeJson(writeJson(snapshot));
        event.setAfterJson(writeJson(BudgetTargetAuditSupport.executionEventState(
                execution,
                execution.getOperatorId(),
                errorCode,
                payloadOf(
                        "executionStatus", execution.getExecutionState().name(),
                        "errorCode", errorCode,
                        "payload", payload,
                        "positionSlot", execution.getPositionSlot(),
                        "sessionId", execution.getSession() != null ? execution.getSession().getId() : null,
                        "requiresIntervention", execution.isRequiresIntervention(),
                        "criticalIssue", readJson(execution.getCriticalIssueJson())))));
        event.setEventTs(Instant.now());
        LiveTradeExecutionEvent saved = liveTradeExecutionEventRepository.save(event);
        sessionStreamPublisher.publish(saved);
    }

    private void refreshSessionRollup(LiveTradeExecution execution) {
        if (execution.getSession() == null) {
            return;
        }
        liveTradePersistenceService.refreshSessionRollup(
                execution.getSession(),
                LiveExecutionTransactionService.ACTIVE_STATES_FOR_SLOTS);
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> payloadOf(Object... entries) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (entries == null) {
            return payload;
        }
        for (int index = 0; index + 1 < entries.length; index += 2) {
            Object key = entries[index];
            if (!(key instanceof String stringKey)) {
                continue;
            }
            payload.put(stringKey, entries[index + 1]);
        }
        return payload;
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

    private String firstBlockedCode(Collection<LiveTradeBlockedReasonDTO> blockedReasons) {
        return blockedReasons == null || blockedReasons.isEmpty() ? "PREFLIGHT_REJECTED" : blockedReasons.iterator().next().getCode();
    }

    private String firstBlockedMessage(Collection<LiveTradeBlockedReasonDTO> blockedReasons) {
        return blockedReasons == null || blockedReasons.isEmpty()
                ? "Execution blocked by preflight."
                : blockedReasons.iterator().next().getMessage();
    }

    private LiveTradeExecutionDTO toDetail(LiveTradeExecution execution) {
        return liveTradingMapper.toDetail(execution,
                liveTradeExecutionEventRepository.findByExecution_IdOrderByCreatedAtAsc(execution.getId()));
    }

    private SafeCloseAttemptResult safeCloseResult(UUID executionId,
            SafeCloseAttemptResult.SafeCloseAttemptStatus status,
            LiveTradeExecutionDTO execution,
            String errorCode,
            String errorMessage,
            Map<String, Object> details) {
        return new SafeCloseAttemptResult(
                executionId,
                status,
                execution,
                errorCode,
                errorMessage,
                details == null ? Map.of() : details);
    }

    private record ProtectionValidation(boolean valid, String message, Map<String, Object> details) {
        private static ProtectionValidation passed() {
            return new ProtectionValidation(true, null, Map.of());
        }

        private static ProtectionValidation invalid(String message, Map<String, Object> details) {
            return new ProtectionValidation(false, message, details);
        }
    }

    private record ProtectionLegSubmission(
            String legKey,
            String legLabel,
            String clientAlgoId,
            Long algoId,
            boolean success,
            String errorCode,
            String errorMessage,
            Map<String, Object> summary) {
        private static ProtectionLegSubmission success(String legKey,
                String legLabel,
                String clientAlgoId,
                BinanceFuturesAlgoOrderResponse response,
                Map<String, Object> summary) {
            return new ProtectionLegSubmission(
                    legKey,
                    legLabel,
                    clientAlgoId,
                    response != null ? response.getAlgoId() : null,
                    true,
                    null,
                    null,
                    summary);
        }

        private static ProtectionLegSubmission failure(String legKey,
                String legLabel,
                String clientAlgoId,
                String errorCode,
                String errorMessage,
                Map<String, Object> details) {
            return new ProtectionLegSubmission(
                    legKey,
                    legLabel,
                    clientAlgoId,
                    null,
                    false,
                    errorCode,
                    errorMessage,
                    Map.of(
                            "legKey", legKey,
                            "legLabel", legLabel,
                            "clientAlgoId", clientAlgoId,
                            "errorCode", errorCode,
                            "errorMessage", errorMessage,
                            "details", details == null ? Map.of() : details));
        }
    }

    private record ProtectionFailureSummary(
            String errorCode,
            String errorMessage,
            String operatorMessage,
            boolean downsideProtected,
            Map<String, Object> summary,
            Map<String, Object> criticalIssue) {
    }
}
