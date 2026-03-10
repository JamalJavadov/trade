package com.tradebot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
import com.tradebot.dto.BinanceFuturesAlgoOrderResponse;
import com.tradebot.dto.BinanceFuturesOrderResponse;
import com.tradebot.dto.BinanceFuturesPositionRiskResponse;
import com.tradebot.dto.BinanceOrderFieldsDTO;
import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionEvent;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.repository.LiveTradeExecutionEventRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import lombok.RequiredArgsConstructor;
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
public class OrderStateSyncService {

    private static final int ENTRY_FILL_LOOKUP_ATTEMPTS = 5;
    private static final long ENTRY_FILL_LOOKUP_DELAY_MS = 400L;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<LiveTradeExecutionState> RECONCILE_STATES = EnumSet.of(
            LiveTradeExecutionState.ENTRY_SUBMITTED,
            LiveTradeExecutionState.ENTRY_FILLED,
            LiveTradeExecutionState.PROTECTION_SUBMITTING,
            LiveTradeExecutionState.PROTECTION_ACTIVE,
            LiveTradeExecutionState.ACTIVE,
            LiveTradeExecutionState.CLOSING,
            LiveTradeExecutionState.RECONCILING);

    private final LiveTradeExecutionRepository liveTradeExecutionRepository;
    private final LiveTradeExecutionEventRepository liveTradeExecutionEventRepository;
    private final BinanceClient binanceClient;
    private final LiveTradingMapper liveTradingMapper;
    private final LiveTradePersistenceService liveTradePersistenceService;
    private final BudgetTargetAutoExecutionLifecycleService lifecycleService;
    private final ObjectMapper objectMapper;
    private final BinanceExecutionResponseParser responseParser;
    private final BudgetTargetSessionStreamPublisher sessionStreamPublisher;
    private final ExchangeSyncSnapshotService exchangeSyncSnapshotService;

    public LiveTradeExecutionDTO reconcileExecution(UUID executionId, String actor, String traceId, boolean scheduled) {
        LiveTradeExecution execution = liveTradeExecutionRepository.findById(executionId)
                .orElseThrow(() -> new NoSuchElementException("Live execution not found: " + executionId));
        LiveTradeExecutionState previousState = execution.getExecutionState();

        if (execution.isDryRun()) {
            persistSkippedSnapshot(
                    execution,
                    scheduled,
                    "DRY_RUN_EXECUTION",
                    "Dry-run executions do not require Binance reconciliation.",
                    payloadOf("scheduled", scheduled));
            appendEvent(execution, "RECONCILE_SKIPPED", execution.getExecutionState().name(),
                    "Dry-run executions do not require Binance reconciliation.",
                    null,
                    payloadOf("scheduled", scheduled));
            return toDetail(execution);
        }
        if (!RECONCILE_STATES.contains(execution.getExecutionState()) || !hasLookupableOrders(execution)) {
            persistSkippedSnapshot(
                    execution,
                    scheduled,
                    "RECONCILE_INELIGIBLE",
                    "Execution state is not eligible for Binance reconciliation.",
                    payloadOf("scheduled", scheduled));
            appendEvent(execution, "RECONCILE_SKIPPED", execution.getExecutionState().name(),
                    "Execution state is not eligible for Binance reconciliation.",
                    null,
                    payloadOf("scheduled", scheduled));
            return toDetail(execution);
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

            exchangeResponses.put("entry", responseParser.mergeSection(exchangeResponses.get("entry"), responseParser.orderSummary(entry)));
            exchangeResponses.put("stopLoss", responseParser.mergeSection(exchangeResponses.get("stopLoss"), responseParser.algoSummary(stopLoss)));
            exchangeResponses.put("takeProfit", responseParser.mergeSection(exchangeResponses.get("takeProfit"), responseParser.algoSummary(takeProfit)));
            exchangeResponses.put("emergencyClose",
                    responseParser.mergeSection(exchangeResponses.get("emergencyClose"), responseParser.orderSummary(emergencyClose)));
            exchangeResponses.put("position", responseParser.positionSummary(position));

            ReconciledOutcome outcome = resolveState(execution, entry, stopLoss, takeProfit, openAlgoOrders, emergencyClose, position);
            exchangeResponses.put("reconciliation", outcome.summary());
            syncOrderSnapshots(execution, entry, stopLoss, takeProfit, emergencyClose, exchangeResponses);

            execution.setExecutionState(outcome.state());
            execution.setRequiresIntervention(outcome.requiresIntervention());
            execution.setCriticalIssueJson(writeJson(outcome.criticalIssue()));
            execution.setErrorCode(outcome.errorCode());
            execution.setErrorMessage(outcome.errorMessage());
            execution.setErrorDetailsJson(writeJson(outcome.errorDetails()));

            TradeOutcomeSummary tradeOutcome = null;
            if (outcome.state() == LiveTradeExecutionState.CLOSED) {
                tradeOutcome = enrichTerminalOutcome(execution, stopLoss, takeProfit, emergencyClose);
                if (tradeOutcome.pnlResolved()) {
                    execution.setRequiresIntervention(false);
                    execution.setCriticalIssueJson(null);
                    execution.setErrorCode(null);
                    execution.setErrorMessage(null);
                    execution.setErrorDetailsJson(null);
                } else {
                    execution.setRequiresIntervention(true);
                    execution.setCriticalIssueJson(writeJson(criticalIssue(
                            LiveTradingBlockerCodes.REALIZED_PNL_UNRESOLVED,
                            tradeOutcome.unresolvedMessage(),
                            tradeOutcome.details())));
                    execution.setErrorCode(LiveTradingBlockerCodes.REALIZED_PNL_UNRESOLVED);
                    execution.setErrorMessage(tradeOutcome.unresolvedMessage());
                    execution.setErrorDetailsJson(writeJson(tradeOutcome.details()));
                }
            }
            if (outcome.state() == LiveTradeExecutionState.ACTIVE && !outcome.requiresIntervention()) {
                execution.setErrorCode(null);
                execution.setErrorMessage(null);
                execution.setErrorDetailsJson(null);
            }
            execution.setExchangeResponseJson(writeJson(exchangeResponses));
            execution.setLastReconciledAt(Instant.now());
            execution.setUpdatedAt(Instant.now());
            execution.setReconcileCount(execution.getReconcileCount() + 1);
            if (outcome.state() == LiveTradeExecutionState.CLOSED && execution.getCompletedAt() == null) {
                execution.setCompletedAt(Instant.now());
            }
            liveTradeExecutionRepository.save(execution);
            if (tradeOutcome != null) {
                persistTerminalState(execution, stopLoss, takeProfit, emergencyClose, position, exchangeResponses, tradeOutcome);
            }
            persistSuccessSnapshot(execution, scheduled, exchangeResponses, outcome, tradeOutcome, traceId, actor);
            appendSessionMismatchEventIfNeeded(execution, previousState, outcome);
            appendEvent(execution, scheduled ? "SCHEDULED_RECONCILE" : "RECONCILE",
                    execution.getExecutionState().name(),
                    "Reconciled live execution against Binance order and position truth.",
                    outcome.errorCode(),
                    payloadOf(
                            "scheduled", scheduled,
                            "actor", actor == null || actor.isBlank() ? "system" : actor,
                            "traceId", traceId,
                            "statuses", outcome.summary(),
                            "criticalIssue", outcome.criticalIssue()));
            return toDetail(execution);
        } catch (WebClientRequestException ex) {
            execution.setExecutionState(LiveTradeExecutionState.RECONCILING);
            execution.setErrorCode(LiveTradingBlockerCodes.UPSTREAM_TIMEOUT);
            execution.setErrorMessage("Reconciliation timed out. Retry later.");
            execution.setErrorDetailsJson(writeJson(Map.of("message", ex.getMessage())));
            execution.setLastReconciledAt(Instant.now());
            execution.setUpdatedAt(Instant.now());
            execution.setReconcileCount(execution.getReconcileCount() + 1);
            liveTradeExecutionRepository.save(execution);
            persistFailureSnapshot(
                    execution,
                    scheduled,
                    execution.getErrorCode(),
                    execution.getErrorMessage(),
                    traceId,
                    payloadOf("message", ex.getMessage()));
            appendEvent(execution, "RECONCILING", execution.getExecutionState().name(),
                    "Binance reconciliation timed out.",
                    execution.getErrorCode(),
                    payloadOf("message", ex.getMessage(), "traceId", traceId));
            return toDetail(execution);
        } catch (WebClientResponseException ex) {
            execution.setExecutionState(LiveTradeExecutionState.RECONCILING);
            execution.setErrorCode("BINANCE_RECONCILE_REJECTED");
            execution.setErrorMessage(ex.getMessage());
            execution.setErrorDetailsJson(writeJson(Map.of("status", ex.getRawStatusCode(), "message", ex.getMessage())));
            execution.setLastReconciledAt(Instant.now());
            execution.setUpdatedAt(Instant.now());
            execution.setReconcileCount(execution.getReconcileCount() + 1);
            liveTradeExecutionRepository.save(execution);
            persistFailureSnapshot(
                    execution,
                    scheduled,
                    execution.getErrorCode(),
                    execution.getErrorMessage(),
                    traceId,
                    payloadOf("status", ex.getRawStatusCode(), "message", ex.getMessage()));
            appendEvent(execution, "RECONCILING", execution.getExecutionState().name(),
                    "Binance rejected reconciliation lookup.",
                    execution.getErrorCode(),
                    payloadOf("status", ex.getRawStatusCode(), "message", ex.getMessage(), "traceId", traceId));
            return toDetail(execution);
        }
    }

    public EntryFillResolution resolveEntryFill(LiveTradeExecution execution,
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

    public BinanceFuturesPositionRiskResponse lookupPosition(String symbol, String entrySide) {
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
            BigDecimal positionAmt = responseParser.signedDecimal(position.getPositionAmt());
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

    public BigDecimal positionRiskQuantity(BinanceFuturesPositionRiskResponse positionRisk) {
        if (positionRisk == null) {
            return null;
        }
        BigDecimal positionAmt = responseParser.signedDecimal(positionRisk.getPositionAmt());
        return positionAmt == null ? null : positionAmt.abs();
    }

    private LiveTradeExecutionDTO toDetail(LiveTradeExecution execution) {
        LiveTradeExecutionDTO dto = liveTradingMapper.toDetail(
                execution,
                liveTradeExecutionEventRepository.findByExecution_IdOrderByCreatedAtAsc(execution.getId()));
        dto.setSyncHealth(exchangeSyncSnapshotService.summarizeExecution(execution.getId()));
        return dto;
    }

    private ReconciledOutcome resolveState(LiveTradeExecution execution,
            BinanceFuturesOrderResponse entry,
            BinanceFuturesAlgoOrderResponse stopLoss,
            BinanceFuturesAlgoOrderResponse takeProfit,
            List<BinanceFuturesAlgoOrderResponse> openAlgoOrders,
            BinanceFuturesOrderResponse emergencyClose,
            BinanceFuturesPositionRiskResponse position) {
        boolean hasPosition = responseParser.hasOpenPosition(position);
        boolean stopLossActive = responseParser.isAlgoActive(stopLoss, openAlgoOrders);
        boolean takeProfitActive = responseParser.isAlgoActive(takeProfit, openAlgoOrders);
        boolean emergencyCloseFilled = responseParser.isFilled(emergencyClose);
        boolean emergencyCloseWorking = responseParser.isWorking(emergencyClose);
        boolean protectionTriggered = responseParser.isTriggered(stopLoss) || responseParser.isTriggered(takeProfit);
        BigDecimal positionQty = responseParser.absolutePositionQty(position);
        boolean resolvedFlatWithoutPosition = !hasPosition && shouldResolveClosedWithoutPosition(
                execution,
                entry,
                emergencyCloseFilled,
                protectionTriggered);

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
        summary.put("resolvedFlatWithoutPosition", resolvedFlatWithoutPosition);

        if (!hasPosition) {
            if (resolvedFlatWithoutPosition) {
                return new ReconciledOutcome(
                        LiveTradeExecutionState.CLOSED,
                        null,
                        null,
                        Map.of(),
                        false,
                        null,
                        summary);
            }
            return new ReconciledOutcome(
                    LiveTradeExecutionState.RECONCILING,
                    execution.getErrorCode(),
                    execution.getErrorMessage(),
                    errorDetails("NO_POSITION_CONFIRMATION", "Position is not yet closed or confirmed."),
                    execution.isRequiresIntervention(),
                    readJson(execution.getCriticalIssueJson()),
                    summary);
        }

        if ((execution.getSlClientOrderId() == null && execution.getSlOrderId() == null)
                && (execution.getTpClientOrderId() == null && execution.getTpOrderId() == null)) {
            return new ReconciledOutcome(
                    LiveTradeExecutionState.ENTRY_FILLED,
                    execution.getErrorCode(),
                    execution.getErrorMessage(),
                    readJson(execution.getErrorDetailsJson()),
                    execution.isRequiresIntervention(),
                    readJson(execution.getCriticalIssueJson()),
                    summary);
        }

        if (stopLossActive && takeProfitActive) {
            return new ReconciledOutcome(
                    LiveTradeExecutionState.ACTIVE,
                    null,
                    null,
                    Map.of(),
                    false,
                    null,
                    summary);
        }

        if (stopLossActive) {
            Map<String, Object> criticalIssue = criticalIssue(
                    "TAKE_PROFIT_MISSING",
                    "Take-profit protection is missing, but stop-loss remains active.",
                    Map.of("takeProfitStatus", takeProfit != null ? takeProfit.getAlgoStatus() : "MISSING"));
            return new ReconciledOutcome(
                    LiveTradeExecutionState.ACTIVE,
                    LiveTradingBlockerCodes.BINANCE_REJECTED,
                    "Take-profit protection is missing, but stop-loss remains active.",
                    errorDetails("TAKE_PROFIT_MISSING", "Take-profit protection is missing."),
                    true,
                    criticalIssue,
                    summary);
        }

        Map<String, Object> criticalIssue = criticalIssue(
                "STOP_LOSS_MISSING",
                emergencyCloseWorking
                        ? "Stop-loss protection failed and an emergency close is in flight."
                        : "Stop-loss protection is missing while Binance still reports an open position.",
                Map.of(
                        "stopLossStatus", stopLoss != null ? stopLoss.getAlgoStatus() : "MISSING",
                        "emergencyCloseStatus", emergencyClose != null ? emergencyClose.getStatus() : "NONE"));
        if (emergencyCloseWorking) {
            return new ReconciledOutcome(
                    LiveTradeExecutionState.CLOSING,
                    LiveTradingBlockerCodes.BINANCE_REJECTED,
                    "Emergency close is in flight after stop-loss protection failed.",
                    errorDetails("EMERGENCY_CLOSE_IN_FLIGHT", "Emergency close is in flight."),
                    true,
                    criticalIssue,
                    summary);
        }
        return new ReconciledOutcome(
                LiveTradeExecutionState.RECONCILING,
                execution.getErrorCode() != null ? execution.getErrorCode() : LiveTradingBlockerCodes.BINANCE_REJECTED,
                execution.getErrorMessage() != null
                        ? execution.getErrorMessage()
                        : "Stop-loss protection is not active while Binance still reports an open position.",
                errorDetails("STOP_LOSS_MISSING", "Stop-loss protection is missing."),
                true,
                criticalIssue,
                    summary);
    }

    private boolean shouldResolveClosedWithoutPosition(LiveTradeExecution execution,
            BinanceFuturesOrderResponse entry,
            boolean emergencyCloseFilled,
            boolean protectionTriggered) {
        if (emergencyCloseFilled || protectionTriggered || responseParser.isFilled(entry)) {
            return true;
        }
        if (execution.getActualFilledQty() != null && execution.getActualFilledQty().compareTo(BigDecimal.ZERO) > 0) {
            return true;
        }
        return switch (execution.getExecutionState()) {
            case ENTRY_FILLED, PROTECTION_SUBMITTING, PROTECTION_ACTIVE, ACTIVE, CLOSING, RECONCILING -> true;
            default -> false;
        };
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
        BinanceFuturesAlgoOrderResponse openMatch = responseParser.findOpenAlgoOrder(clientAlgoId, algoId, openAlgoOrders);
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

    private EntryFillResolution entryFillFromOrder(String source,
            BinanceFuturesOrderResponse response,
            BinanceFuturesPositionRiskResponse positionRisk) {
        BigDecimal executedQty = responseParser.positiveDecimal(response != null ? response.getExecutedQty() : null);
        BigDecimal avgPrice = responseParser.positiveDecimal(response != null ? response.getAvgPrice() : null);
        BigDecimal positionQuantity = positionRiskQuantity(positionRisk);
        BigDecimal positionEntryPrice = responseParser.positiveDecimal(positionRisk != null ? positionRisk.getEntryPrice() : null);
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
                responseParser.orderSummary(response),
                responseParser.positionSummary(positionRisk));
    }

    private EntryFillResolution entryFillFromPosition(String source,
            BinanceFuturesPositionRiskResponse positionRisk,
            BinanceFuturesOrderResponse response) {
        BigDecimal positionQuantity = positionRiskQuantity(positionRisk);
        BigDecimal positionEntryPrice = responseParser.positiveDecimal(positionRisk != null ? positionRisk.getEntryPrice() : null);
        BigDecimal avgPrice = responseParser.positiveDecimal(response != null ? response.getAvgPrice() : null);
        String orderStatus = response != null ? response.getStatus() : null;
        return new EntryFillResolution(
                source,
                resolvedEntryState(orderStatus, positionQuantity),
                nonNegative(positionQuantity),
                positiveOrZero(avgPrice),
                positiveOrZero(positionEntryPrice),
                orderStatus,
                responseParser.orderSummary(response),
                responseParser.positionSummary(positionRisk));
    }

    private LiveTradeExecutionState resolvedEntryState(String orderStatus, BigDecimal positionQuantity) {
        if (positionQuantity == null || positionQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return LiveTradeExecutionState.ENTRY_SUBMITTED;
        }
        return LiveTradeExecutionState.ENTRY_FILLED;
    }

    private void pauseBetweenFillLookups() {
        try {
            Thread.sleep(ENTRY_FILL_LOOKUP_DELAY_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private BigDecimal positiveOrZero(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? BigDecimal.ZERO : value;
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : value;
    }

    private Map<String, Object> errorDetails(String code, String message) {
        return Map.of("code", code, "message", message);
    }

    private Map<String, Object> criticalIssue(String code, String message, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("details", details == null ? Map.of() : details);
        payload.put("raisedAt", Instant.now().toString());
        return payload;
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
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
                        "closeReason", execution.getCloseReason(),
                        "requiresIntervention", execution.isRequiresIntervention()))));
        event.setEventTs(Instant.now());
        LiveTradeExecutionEvent saved = liveTradeExecutionEventRepository.save(event);
        sessionStreamPublisher.publish(saved);
    }

    private void appendSessionMismatchEventIfNeeded(LiveTradeExecution execution,
            LiveTradeExecutionState previousState,
            ReconciledOutcome outcome) {
        if (execution.getSession() == null
                || outcome.state() != LiveTradeExecutionState.CLOSED
                || !Boolean.TRUE.equals(outcome.summary().get("resolvedFlatWithoutPosition"))) {
            return;
        }
        lifecycleService.appendSessionEvent(
                execution.getSession(),
                execution,
                "EXECUTION_AUTO_HEALED",
                "Exchange reconciliation auto-closed a session-owned execution because Binance reported no open position.",
                "EXCHANGE_POSITION_FLAT",
                payloadOf(
                        "previousState", previousState.name(),
                        "currentState", outcome.state().name(),
                        "summary", outcome.summary()));
    }

    private void syncOrderSnapshots(LiveTradeExecution execution,
            BinanceFuturesOrderResponse entry,
            BinanceFuturesAlgoOrderResponse stopLoss,
            BinanceFuturesAlgoOrderResponse takeProfit,
            BinanceFuturesOrderResponse emergencyClose,
            Map<String, Object> snapshotPayload) {
        liveTradePersistenceService.upsertOrder(
                execution,
                "ENTRY",
                execution.getEntryClientOrderId(),
                execution.getEntryOrderId(),
                null,
                null,
                execution.getRequestedQty(),
                responseParser.positiveDecimal(entry != null ? entry.getExecutedQty() : null),
                null,
                null,
                responseParser.positiveDecimal(entry != null ? entry.getAvgPrice() : null),
                entry != null ? entry.getStatus() : execution.getExecutionState().name(),
                null,
                responseParser.orderSummary(entry),
                snapshotPayload);

        if (execution.getSlClientOrderId() != null || execution.getSlOrderId() != null) {
            liveTradePersistenceService.upsertOrder(
                    execution,
                    "STOP_LOSS",
                    null,
                    null,
                    execution.getSlClientOrderId(),
                    execution.getSlOrderId(),
                    execution.getActualFilledQty(),
                    responseParser.positiveDecimal(stopLoss != null ? stopLoss.getExecutedQty() : null),
                    null,
                    responseParser.positiveDecimal(stopLoss != null ? stopLoss.getTriggerPrice() : null),
                    responseParser.positiveDecimal(stopLoss != null ? stopLoss.getAvgPrice() : null),
                    stopLoss != null ? stopLoss.getAlgoStatus() : execution.getExecutionState().name(),
                    null,
                    responseParser.algoSummary(stopLoss),
                    snapshotPayload);
        }

        if (execution.getTpClientOrderId() != null || execution.getTpOrderId() != null) {
            liveTradePersistenceService.upsertOrder(
                    execution,
                    "TAKE_PROFIT",
                    null,
                    null,
                    execution.getTpClientOrderId(),
                    execution.getTpOrderId(),
                    execution.getActualFilledQty(),
                    responseParser.positiveDecimal(takeProfit != null ? takeProfit.getExecutedQty() : null),
                    null,
                    responseParser.positiveDecimal(takeProfit != null ? takeProfit.getTriggerPrice() : null),
                    responseParser.positiveDecimal(takeProfit != null ? takeProfit.getAvgPrice() : null),
                    takeProfit != null ? takeProfit.getAlgoStatus() : execution.getExecutionState().name(),
                    null,
                    responseParser.algoSummary(takeProfit),
                    snapshotPayload);
        }

        if (execution.getEmergencyCloseClientOrderId() != null || execution.getEmergencyCloseOrderId() != null) {
            liveTradePersistenceService.upsertOrder(
                    execution,
                    "EMERGENCY_CLOSE",
                    execution.getEmergencyCloseClientOrderId(),
                    execution.getEmergencyCloseOrderId(),
                    null,
                    null,
                    execution.getActualFilledQty(),
                    responseParser.positiveDecimal(emergencyClose != null ? emergencyClose.getExecutedQty() : null),
                    null,
                    null,
                    responseParser.positiveDecimal(emergencyClose != null ? emergencyClose.getAvgPrice() : null),
                    emergencyClose != null ? emergencyClose.getStatus() : execution.getExecutionState().name(),
                    null,
                    responseParser.orderSummary(emergencyClose),
                    snapshotPayload);
        }
    }

    private TradeOutcomeSummary enrichTerminalOutcome(LiveTradeExecution execution,
            BinanceFuturesAlgoOrderResponse stopLoss,
            BinanceFuturesAlgoOrderResponse takeProfit,
            BinanceFuturesOrderResponse emergencyClose) {
        TradeOutcomeSummary summary = resolveTradeOutcome(execution, stopLoss, takeProfit, emergencyClose);
        execution.setRealizedGrossPnlUsdt(summary.realizedGrossPnlUsdt());
        execution.setRealizedFeesUsdt(summary.realizedFeesUsdt());
        execution.setRealizedNetPnlUsdt(summary.realizedNetPnlUsdt());
        if (summary.closeReason() != null && !summary.closeReason().isBlank()) {
            execution.setCloseReason(summary.closeReason());
        }
        return summary;
    }

    private TradeOutcomeSummary resolveTradeOutcome(LiveTradeExecution execution,
            BinanceFuturesAlgoOrderResponse stopLoss,
            BinanceFuturesAlgoOrderResponse takeProfit,
            BinanceFuturesOrderResponse emergencyClose) {
        String closeReason = determineCloseReason(execution, stopLoss, takeProfit, emergencyClose);
        List<Map<String, Object>> entryTrades = execution.getEntryOrderId() != null
                ? safeUserTrades(execution.getSymbol(), execution.getEntryOrderId())
                : List.of();
        Long closeOrderId = resolveCloseOrderId(stopLoss, takeProfit, emergencyClose);
        List<Map<String, Object>> closeTrades = closeOrderId != null
                ? safeUserTrades(execution.getSymbol(), closeOrderId)
                : List.of();

        if (!entryTrades.isEmpty() || !closeTrades.isEmpty()) {
            BigDecimal gross = sumDecimalField(entryTrades, "realizedPnl").add(sumDecimalField(closeTrades, "realizedPnl"));
            BigDecimal fees = sumAbsDecimalField(entryTrades, "commission").add(sumAbsDecimalField(closeTrades, "commission"));
            return TradeOutcomeSummary.resolved(
                    gross,
                    fees,
                    closeReason,
                    "USER_TRADES",
                    payloadOf(
                            "closeReason", closeReason,
                            "entryTradeCount", entryTrades.size(),
                            "closeTradeCount", closeTrades.size(),
                            "closeOrderId", closeOrderId));
        }

        IncomeOutcome incomeOutcome = safeIncomeOutcome(execution);
        if (incomeOutcome.resolved()) {
            return TradeOutcomeSummary.resolved(
                    incomeOutcome.realizedPnl(),
                    incomeOutcome.commission(),
                    closeReason,
                    incomeOutcome.source(),
                    payloadOf(
                            "closeReason", closeReason,
                            "source", incomeOutcome.source(),
                            "details", incomeOutcome.details()));
        }

        return TradeOutcomeSummary.unresolved(
                closeReason,
                incomeOutcome.message(),
                payloadOf(
                        "closeReason", closeReason,
                        "closeOrderId", closeOrderId,
                        "entryTradesResolved", false,
                        "incomeSource", incomeOutcome.source(),
                        "details", incomeOutcome.details()));
    }

    private List<Map<String, Object>> safeUserTrades(String symbol, Long orderId) {
        try {
            List<Map<String, Object>> rows = binanceClient.getUserTrades(symbol, orderId, null, null);
            return rows == null ? List.of() : rows;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private IncomeOutcome safeIncomeOutcome(LiveTradeExecution execution) {
        if (execution.getSubmittedAt() == null || execution.getCompletedAt() == null) {
            return IncomeOutcome.unresolved(
                    "Submitted/completed timestamps are unavailable for Binance income lookups.",
                    payloadOf(
                            "submittedAt", execution.getSubmittedAt(),
                            "completedAt", execution.getCompletedAt()));
        }
        long startTimeMs = execution.getSubmittedAt().minusSeconds(60).toEpochMilli();
        long endTimeMs = execution.getCompletedAt().plusSeconds(60).toEpochMilli();
        try {
            List<Map<String, Object>> realizedRows = binanceClient.getIncomeHistory(
                    execution.getSymbol(),
                    "REALIZED_PNL",
                    startTimeMs,
                    endTimeMs,
                    100);
            List<Map<String, Object>> commissionRows = binanceClient.getIncomeHistory(
                    execution.getSymbol(),
                    "COMMISSION",
                    startTimeMs,
                    endTimeMs,
                    100);
            if ((realizedRows == null || realizedRows.isEmpty()) && (commissionRows == null || commissionRows.isEmpty())) {
                return IncomeOutcome.unresolved(
                        "Binance income history returned no realized PnL or commission rows for the closed execution.",
                        payloadOf(
                                "symbol", execution.getSymbol(),
                                "startTimeMs", startTimeMs,
                                "endTimeMs", endTimeMs));
            }
            return IncomeOutcome.resolved(
                    sumDecimalField(realizedRows, "income"),
                    sumAbsDecimalField(commissionRows, "income"),
                    "INCOME_HISTORY",
                    payloadOf(
                            "realizedRowCount", realizedRows == null ? 0 : realizedRows.size(),
                            "commissionRowCount", commissionRows == null ? 0 : commissionRows.size(),
                            "startTimeMs", startTimeMs,
                            "endTimeMs", endTimeMs));
        } catch (Exception ex) {
            return IncomeOutcome.unresolved(
                    "Binance income history lookup failed while resolving realized net PnL.",
                    payloadOf(
                            "symbol", execution.getSymbol(),
                            "message", ex.getMessage(),
                            "startTimeMs", startTimeMs,
                            "endTimeMs", endTimeMs));
        }
    }

    private String determineCloseReason(LiveTradeExecution execution,
            BinanceFuturesAlgoOrderResponse stopLoss,
            BinanceFuturesAlgoOrderResponse takeProfit,
            BinanceFuturesOrderResponse emergencyClose) {
        if (execution.getCloseReason() != null && !execution.getCloseReason().isBlank()) {
            return execution.getCloseReason();
        }
        if (responseParser.isFilled(emergencyClose)) {
            return "EMERGENCY_CLOSE";
        }
        if (responseParser.isTriggered(stopLoss)) {
            return "STOP_LOSS_TRIGGERED";
        }
        if (responseParser.isTriggered(takeProfit)) {
            return "TAKE_PROFIT_TRIGGERED";
        }
        return "POSITION_CLOSED";
    }

    private Long resolveCloseOrderId(BinanceFuturesAlgoOrderResponse stopLoss,
            BinanceFuturesAlgoOrderResponse takeProfit,
            BinanceFuturesOrderResponse emergencyClose) {
        if (emergencyClose != null && emergencyClose.getOrderId() != null) {
            return emergencyClose.getOrderId();
        }
        Long stopLossActualOrderId = responseParser.parseLong(stopLoss != null ? stopLoss.getActualOrderId() : null);
        if (stopLossActualOrderId != null && stopLossActualOrderId > 0) {
            return stopLossActualOrderId;
        }
        Long takeProfitActualOrderId = responseParser.parseLong(takeProfit != null ? takeProfit.getActualOrderId() : null);
        if (takeProfitActualOrderId != null && takeProfitActualOrderId > 0) {
            return takeProfitActualOrderId;
        }
        return null;
    }

    private void persistTerminalState(LiveTradeExecution execution,
            BinanceFuturesAlgoOrderResponse stopLoss,
            BinanceFuturesAlgoOrderResponse takeProfit,
            BinanceFuturesOrderResponse emergencyClose,
            BinanceFuturesPositionRiskResponse position,
            Map<String, Object> exchangeResponses,
            TradeOutcomeSummary tradeOutcome) {
        liveTradePersistenceService.upsertClosure(
                execution,
                tradeOutcome.closeReason(),
                execution.getActualFilledQty(),
                resolveClosedPrice(stopLoss, takeProfit, emergencyClose),
                resolveClosingClientOrderId(execution, tradeOutcome.closeReason()),
                resolveCloseOrderId(stopLoss, takeProfit, emergencyClose),
                responseParser.positionSummary(position),
                payloadOf(
                        "stopLoss", responseParser.algoSummary(stopLoss),
                        "takeProfit", responseParser.algoSummary(takeProfit),
                        "emergencyClose", responseParser.orderSummary(emergencyClose),
                        "exchangeResponses", exchangeResponses),
                execution.getCompletedAt() != null ? execution.getCompletedAt() : Instant.now());

        if (!tradeOutcome.pnlResolved()) {
            if (execution.getSession() != null) {
                lifecycleService.setVisibleFailure(
                        execution.getSession(),
                        LiveTradingBlockerCodes.REALIZED_PNL_UNRESOLVED,
                        tradeOutcome.unresolvedMessage());
                lifecycleService.appendSessionEvent(
                        execution.getSession(),
                        execution,
                        "EXECUTION_PNL_UNRESOLVED",
                        tradeOutcome.unresolvedMessage(),
                        LiveTradingBlockerCodes.REALIZED_PNL_UNRESOLVED,
                        tradeOutcome.details());
                lifecycleService.requestStop(
                        execution.getSession(),
                        com.tradebot.entity.BudgetTargetSessionCompletionReason.FATAL_SYNC_ERROR,
                        "system",
                        "Session is stopping because a closed trade could not resolve realized net PnL from Binance truth.");
            }
            return;
        }

        if (execution.getSession() != null) {
            liveTradePersistenceService.upsertLedgerEntry(
                    execution.getSession(),
                    execution,
                    "REALIZED_GROSS_PNL",
                    tradeOutcome.realizedGrossPnlUsdt(),
                    execution.getCompletedAt(),
                    "EXECUTION_GROSS_PNL",
                    execution.getId() + ":gross",
                    null,
                    payloadOf("closeReason", tradeOutcome.closeReason()),
                    "Persisted realized gross PnL from reconciliation.",
                    execution.getTraceId());
            liveTradePersistenceService.upsertLedgerEntry(
                    execution.getSession(),
                    execution,
                    "COMMISSION_FEE",
                    tradeOutcome.realizedFeesUsdt().negate(),
                    execution.getCompletedAt(),
                    "EXECUTION_COMMISSION_FEE",
                    execution.getId() + ":fee",
                    null,
                    payloadOf("closeReason", tradeOutcome.closeReason()),
                    "Persisted realized fee total from reconciliation.",
                    execution.getTraceId());
            liveTradePersistenceService.refreshExecutionNetPnl(execution);
            liveTradePersistenceService.refreshSessionRollup(execution.getSession(), List.copyOf(RECONCILE_STATES));
        }
    }

    private String resolveClosingClientOrderId(LiveTradeExecution execution, String closeReason) {
        if ("EMERGENCY_CLOSE".equalsIgnoreCase(closeReason)) {
            return execution.getEmergencyCloseClientOrderId();
        }
        if ("STOP_LOSS_TRIGGERED".equalsIgnoreCase(closeReason)) {
            return execution.getSlClientOrderId();
        }
        if ("TAKE_PROFIT_TRIGGERED".equalsIgnoreCase(closeReason)) {
            return execution.getTpClientOrderId();
        }
        return execution.getEmergencyCloseClientOrderId() != null
                ? execution.getEmergencyCloseClientOrderId()
                : execution.getTpClientOrderId() != null ? execution.getTpClientOrderId() : execution.getSlClientOrderId();
    }

    private BigDecimal resolveClosedPrice(BinanceFuturesAlgoOrderResponse stopLoss,
            BinanceFuturesAlgoOrderResponse takeProfit,
            BinanceFuturesOrderResponse emergencyClose) {
        if (responseParser.positiveDecimal(emergencyClose != null ? emergencyClose.getAvgPrice() : null) != null) {
            return responseParser.positiveDecimal(emergencyClose.getAvgPrice());
        }
        if (responseParser.positiveDecimal(stopLoss != null ? stopLoss.getActualPrice() : null) != null) {
            return responseParser.positiveDecimal(stopLoss.getActualPrice());
        }
        if (responseParser.positiveDecimal(takeProfit != null ? takeProfit.getActualPrice() : null) != null) {
            return responseParser.positiveDecimal(takeProfit.getActualPrice());
        }
        return null;
    }

    private BigDecimal sumDecimalField(List<Map<String, Object>> rows, String field) {
        BigDecimal total = BigDecimal.ZERO;
        if (rows == null) {
            return total;
        }
        for (Map<String, Object> row : rows) {
            total = total.add(responseParser.parseDecimal(row != null ? row.get(field) : null));
        }
        return total;
    }

    private BigDecimal sumAbsDecimalField(List<Map<String, Object>> rows, String field) {
        BigDecimal total = BigDecimal.ZERO;
        if (rows == null) {
            return total;
        }
        for (Map<String, Object> row : rows) {
            total = total.add(responseParser.parseDecimal(row != null ? row.get(field) : null).abs());
        }
        return total;
    }

    private void persistSuccessSnapshot(LiveTradeExecution execution,
            boolean scheduled,
            Map<String, Object> exchangeResponses,
            ReconciledOutcome outcome,
            TradeOutcomeSummary tradeOutcome,
            String traceId,
            String actor) {
        recordSnapshot(
                execution,
                scheduled,
                ExchangeSyncSnapshotService.SYNC_STATUS_SUCCESS,
                outcome.errorCode(),
                outcome.errorMessage(),
                outcome.requiresIntervention(),
                exchangeResponses,
                payloadOf(
                        "traceId", traceId,
                        "actor", actor,
                        "tradeOutcome", tradeOutcome == null ? Map.of() : tradeOutcome.details(),
                        "reconciliation", outcome.summary()));
    }

    private void persistFailureSnapshot(LiveTradeExecution execution,
            boolean scheduled,
            String errorCode,
            String errorMessage,
            String traceId,
            Map<String, Object> payload) {
        recordSnapshot(
                execution,
                scheduled,
                ExchangeSyncSnapshotService.SYNC_STATUS_FAILURE,
                errorCode,
                errorMessage,
                false,
                readJson(execution.getExchangeResponseJson()),
                payloadOf(
                        "traceId", traceId,
                        "payload", payload));
    }

    private void persistSkippedSnapshot(LiveTradeExecution execution,
            boolean scheduled,
            String reasonCode,
            String reasonMessage,
            Map<String, Object> payload) {
        recordSnapshot(
                execution,
                scheduled,
                ExchangeSyncSnapshotService.SYNC_STATUS_SKIPPED,
                reasonCode,
                reasonMessage,
                false,
                readJson(execution.getExchangeResponseJson()),
                payload);
    }

    private void recordSnapshot(LiveTradeExecution execution,
            boolean scheduled,
            String syncStatus,
            String errorCode,
            String errorMessage,
            boolean divergenceDetected,
            Map<String, Object> exchangeResponses,
            Map<String, Object> extraPayload) {
        Map<String, Object> responses = exchangeResponses == null ? Map.of() : exchangeResponses;
        Map<String, Object> summary = mapAt(responses, "reconciliation");
        Map<String, Object> position = mapAt(responses, "position");
        Map<String, Object> entry = mapAt(responses, "entry");
        Map<String, Object> stopLoss = mapAt(responses, "stopLoss");
        Map<String, Object> takeProfit = mapAt(responses, "takeProfit");
        Map<String, Object> emergencyClose = mapAt(responses, "emergencyClose");

        boolean hasPosition = booleanAt(summary, "hasPosition");
        boolean stopLossActive = booleanAt(summary, "stopLossActive");
        boolean takeProfitActive = booleanAt(summary, "takeProfitActive");
        boolean emergencyCloseWorking = booleanAt(summary, "emergencyCloseWorking");
        boolean emergencyCloseFilled = booleanAt(summary, "emergencyCloseFilled");
        boolean protectionTriggered = booleanAt(summary, "protectionTriggered");
        String entryOrderStatus = stringAt(summary, "entryStatus", stringAt(entry, "status", null));
        String stopLossStatus = stringAt(summary, "stopLossStatus", stringAt(stopLoss, "algoStatus", null));
        String takeProfitStatus = stringAt(summary, "takeProfitStatus", stringAt(takeProfit, "algoStatus", null));
        String emergencyCloseStatus = stringAt(emergencyClose, "status", null);

        int activeProtectionOrderCount = countTruthy(stopLossActive, takeProfitActive);
        int activeOpenOrderCount = activeProtectionOrderCount
                + countTruthy(isOpenOrderStatus(entryOrderStatus), emergencyCloseWorking);

        exchangeSyncSnapshotService.persistSnapshot(new ExchangeSyncSnapshotService.RecordedSyncSnapshot(
                execution,
                scheduled ? "SCHEDULED" : "MANUAL",
                syncStatus,
                firstNonBlank(execution.getTraceId(), stringAt(extraPayload, "traceId", null)),
                errorCode,
                errorMessage,
                divergenceDetected,
                execution.isRequiresIntervention(),
                hasPosition,
                activeOpenOrderCount,
                activeProtectionOrderCount,
                stopLossActive,
                takeProfitActive,
                emergencyCloseWorking,
                emergencyCloseFilled,
                protectionTriggered,
                entryOrderStatus,
                stopLossStatus,
                takeProfitStatus,
                emergencyCloseStatus,
                decimalAt(summary, "positionQuantity"),
                execution.getActualFilledQty(),
                decimalOr(decimalAt(entry, "avgPrice"), decimalAt(position, "entryPrice")),
                decimalAt(position, "entryPrice"),
                decimalAt(position, "markPrice"),
                execution.getRealizedGrossPnlUsdt(),
                execution.getRealizedFeesUsdt(),
                execution.getRealizedNetPnlUsdt(),
                decimalAt(position, "unRealizedProfit"),
                Instant.now(),
                payloadOf(
                        "executionState", execution.getExecutionState().name(),
                        "exchangeResponses", responses,
                        "payload", extraPayload)));
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

    private Map<String, Object> mapAt(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String stringKey && entry.getValue() != null) {
                    normalized.put(stringKey, entry.getValue());
                }
            }
            return normalized;
        }
        return Map.of();
    }

    private boolean booleanAt(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof Boolean bool && bool;
    }

    private String stringAt(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private BigDecimal decimalAt(Map<String, Object> payload, String key) {
        return responseParser.parseDecimal(payload.get(key));
    }

    private BigDecimal decimalOr(BigDecimal primary, BigDecimal fallback) {
        return primary != null && primary.compareTo(BigDecimal.ZERO) > 0 ? primary : fallback;
    }

    private int countTruthy(boolean... values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private boolean isOpenOrderStatus(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        return Set.of("NEW", "PARTIALLY_FILLED", "ACCEPTED", "WORKING").contains(status.toUpperCase());
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

    public record ReconciledOutcome(
            LiveTradeExecutionState state,
            String errorCode,
            String errorMessage,
            Map<String, Object> errorDetails,
            boolean requiresIntervention,
            Map<String, Object> criticalIssue,
            Map<String, Object> summary) {
    }

    public record EntryFillResolution(
            String source,
            LiveTradeExecutionState executionState,
            BigDecimal filledQuantity,
            BigDecimal avgPrice,
            BigDecimal positionEntryPrice,
            String orderStatus,
            Map<String, Object> orderSummary,
            Map<String, Object> positionSummary) {
        public boolean hasPosition() {
            return filledQuantity != null && filledQuantity.compareTo(BigDecimal.ZERO) > 0;
        }

        public BigDecimal positionQuantity() {
            return filledQuantity == null ? BigDecimal.ZERO : filledQuantity;
        }

        public BigDecimal referencePrice() {
            if (avgPrice != null && avgPrice.compareTo(BigDecimal.ZERO) > 0) {
                return avgPrice;
            }
            if (positionEntryPrice != null && positionEntryPrice.compareTo(BigDecimal.ZERO) > 0) {
                return positionEntryPrice;
            }
            return null;
        }

        public Map<String, Object> entrySummary() {
            Map<String, Object> summary = new LinkedHashMap<>(orderSummary != null ? orderSummary : Map.of());
            summary.put("resolvedSource", source);
            summary.put("resolvedFilledQuantity", filledQuantity);
            summary.put("resolvedAvgPrice", avgPrice);
            summary.put("resolvedPositionEntryPrice", positionEntryPrice);
            summary.put("resolvedState", executionState.name());
            return summary;
        }
    }

    private record TradeOutcomeSummary(
            BigDecimal realizedGrossPnlUsdt,
            BigDecimal realizedFeesUsdt,
            BigDecimal realizedNetPnlUsdt,
            String closeReason,
            boolean pnlResolved,
            String resolutionSource,
            String unresolvedMessage,
            Map<String, Object> details) {
        private static TradeOutcomeSummary resolved(BigDecimal gross,
                BigDecimal fees,
                String closeReason,
                String resolutionSource,
                Map<String, Object> details) {
            BigDecimal safeGross = gross == null ? BigDecimal.ZERO : gross;
            BigDecimal safeFees = fees == null ? BigDecimal.ZERO : fees;
            return new TradeOutcomeSummary(
                    safeGross,
                    safeFees,
                    safeGross.subtract(safeFees),
                    closeReason,
                    true,
                    resolutionSource,
                    null,
                    details == null ? Map.of() : details);
        }

        private static TradeOutcomeSummary unresolved(String closeReason,
                String message,
                Map<String, Object> details) {
            return new TradeOutcomeSummary(
                    null,
                    null,
                    null,
                    closeReason,
                    false,
                    null,
                    message != null ? message : "Realized net PnL could not be confirmed from Binance truth.",
                    details == null ? Map.of() : details);
        }
    }

    private record IncomeOutcome(
            BigDecimal realizedPnl,
            BigDecimal commission,
            boolean resolved,
            String source,
            String message,
            Map<String, Object> details) {
        private static IncomeOutcome resolved(BigDecimal realizedPnl,
                BigDecimal commission,
                String source,
                Map<String, Object> details) {
            return new IncomeOutcome(
                    realizedPnl == null ? BigDecimal.ZERO : realizedPnl,
                    commission == null ? BigDecimal.ZERO : commission,
                    true,
                    source,
                    null,
                    details == null ? Map.of() : details);
        }

        private static IncomeOutcome unresolved(String message, Map<String, Object> details) {
            return new IncomeOutcome(
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    false,
                    null,
                    message,
                    details == null ? Map.of() : details);
        }
    }
}
