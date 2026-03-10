package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
import com.tradebot.dto.BinanceExchangeInfoResponse;
import com.tradebot.dto.BinanceFuturesAlgoOrderResponse;
import com.tradebot.dto.BinanceFuturesAlgoOrderCancelResponse;
import com.tradebot.dto.BinanceFuturesOrderResponse;
import com.tradebot.dto.BinanceOrderFieldsDTO;
import com.tradebot.dto.LiveTradeBlockedReasonDTO;
import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionEvent;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.entity.LiveTradeTriggerMode;
import com.tradebot.entity.OrderFields;
import com.tradebot.entity.Recommendation;
import com.tradebot.repository.BudgetTargetSessionRepository;
import com.tradebot.repository.LiveTradeExecutionEventRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.service.ApprovedExecutionCommand;
import com.tradebot.service.BinanceExecutionResponseParser;
import com.tradebot.service.ClientOrderIdFactory;
import com.tradebot.service.ExchangeExecutionPreflightService;
import com.tradebot.service.ExchangeRoundingService;
import com.tradebot.service.BudgetTargetSessionStreamPublisher;
import com.tradebot.service.LiveExecutionEngineService;
import com.tradebot.service.LiveExecutionTransactionService;
import com.tradebot.service.LiveTradePersistenceService;
import com.tradebot.service.LiveTradingBinanceDiagnosticsService;
import com.tradebot.service.LiveTradingBlockerCodes;
import com.tradebot.service.LiveTradingMapper;
import com.tradebot.service.LiveTradingPreflightService;
import com.tradebot.service.OrderStateSyncService;
import com.tradebot.service.SafeCloseAttemptResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.math.BigDecimal;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveExecutionEngineServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LiveTradingMapper mapper = new LiveTradingMapper(objectMapper);
    private final ClientOrderIdFactory clientOrderIdFactory = new ClientOrderIdFactory();
    private final BinanceExecutionResponseParser responseParser = new BinanceExecutionResponseParser();
    private final ExchangeRoundingService roundingService = new ExchangeRoundingService();

    @Test
    void duplicateRequestReturnsExistingExecutionWithoutSecondSubmit() throws Exception {
        Fixture fixture = fixture();
        Recommendation recommendation = recommendation();

        LiveTradeExecution existing = new LiveTradeExecution();
        existing.setId(UUID.randomUUID());
        existing.setRecommendation(recommendation);
        existing.setTriggerMode(LiveTradeTriggerMode.MANUAL_BUTTON);
        existing.setSymbol("BTCUSDT");
        existing.setSide("BUY");
        existing.setExecutionState(LiveTradeExecutionState.ACTIVE);
        existing.setCreatedAt(Instant.now().minusSeconds(30));
        existing.setUpdatedAt(Instant.now().minusSeconds(5));
        existing.setClientRequestId(UUID.fromString("00000000-0000-0000-0000-000000000101"));
        fixture.stubExisting(existing);

        LiveTradeExecutionDTO result = fixture.service.execute(
                new ApprovedExecutionCommand(
                        recommendation.getId(),
                        null,
                        null,
                        existing.getClientRequestId(),
                        LiveTradeTriggerMode.MANUAL_BUTTON,
                        "operator-1",
                        "trace-dup",
                        "manual",
                        "duplicate test"),
                null);

        assertEquals(existing.getId(), result.getId());
        assertEquals("ACTIVE", result.getExecutionState());
        verify(fixture.binanceClient, never()).submitOrder(any());
    }

    @Test
    void entrySuccessWithTakeProfitFailureReturnsActiveCriticalState() throws Exception {
        Fixture fixture = fixture();
        Recommendation recommendation = recommendation();
        fixture.stubRecommendation(recommendation);
        fixture.stubExecutablePreflight();

        BinanceOrderFieldsDTO entryOrder = order("BTCUSDT", "BUY", "MARKET", new BigDecimal("0.010"), null);
        BinanceOrderFieldsDTO stopLossOrder = order("BTCUSDT", "SELL", "STOP_MARKET", null, new BigDecimal("98000"));
        BinanceOrderFieldsDTO takeProfitOrder = order("BTCUSDT", "SELL", "TAKE_PROFIT_MARKET", null, new BigDecimal("101000"));
        BinanceExchangeInfoResponse.SymbolInfo symbolInfo = symbolInfo();
        ExchangeExecutionPreflightService.ExchangeExecutionPreflightResult exchangePreflight =
                new ExchangeExecutionPreflightService.ExchangeExecutionPreflightResult(
                        new ExchangeExecutionPreflightService.OrderPayloads(entryOrder, stopLossOrder, takeProfitOrder),
                        new ExchangeExecutionPreflightService.OrderPayloads(entryOrder, stopLossOrder, takeProfitOrder),
                        symbolInfo,
                        new BigDecimal("100000"),
                        new BigDecimal("0.1"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.001"),
                        new BigDecimal("5"),
                        10,
                        "ISOLATED",
                        "ONE_WAY",
                        List.of());
        when(fixture.exchangeExecutionPreflightService.evaluate(eq(recommendation), any())).thenReturn(exchangePreflight);

        when(fixture.binanceClient.ensureOneWayPositionMode()).thenReturn(Map.of("status", "UNCHANGED"));
        when(fixture.binanceClient.ensureIsolatedMargin("BTCUSDT")).thenReturn(Map.of("status", "UNCHANGED"));
        when(fixture.binanceClient.setLeverage("BTCUSDT", 10)).thenReturn(Map.of("leverage", 10));
        when(fixture.binanceClient.submitOrder(any()))
                .thenReturn(entryResponse("FILLED", 11L, "0.010", "100000"));
        when(fixture.binanceClient.submitAlgoOrder(any())).thenAnswer(invocation -> {
            Map<String, String> params = invocation.getArgument(0);
            if (params.get("clientAlgoId").endsWith("-sl")) {
                return algoResponse(101L, params.get("clientAlgoId"), "NEW");
            }
            throw WebClientResponseException.create(
                    400,
                    "Bad Request",
                    HttpHeaders.EMPTY,
                    "{}".getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8);
        });
        when(fixture.diagnosticsService.classifyExecutionFailure(any()))
                .thenReturn(new LiveTradeBlockedReasonDTO(
                        LiveTradingBlockerCodes.BINANCE_REJECTED,
                        "Take-profit rejected.",
                        "exchange",
                        Map.of("leg", "takeProfit")));
        when(fixture.orderStateSyncService.resolveEntryFill(any(), any(), any()))
                .thenReturn(new OrderStateSyncService.EntryFillResolution(
                        "entryResult",
                        LiveTradeExecutionState.ENTRY_FILLED,
                        new BigDecimal("0.010"),
                        new BigDecimal("100000"),
                        new BigDecimal("100000"),
                        "FILLED",
                        Map.of("status", "FILLED"),
                        Map.of("positionAmt", "0.010", "entryPrice", "100000")));
        when(fixture.orderStateSyncService.reconcileExecution(any(), any(), any(), eq(false)))
                .thenAnswer(invocation -> fixture.toDetail(invocation.getArgument(0)));

        LiveTradeExecutionDTO result = fixture.service.execute(
                new ApprovedExecutionCommand(
                        recommendation.getId(),
                        null,
                        new BigDecimal("12.50"),
                        UUID.fromString("00000000-0000-0000-0000-000000000202"),
                        LiveTradeTriggerMode.MANUAL_BUTTON,
                        "operator-1",
                        "trace-partial",
                        "manual",
                        "partial failure"),
                null);

        assertEquals("ACTIVE", result.getExecutionState());
        assertTrue(result.isRequiresIntervention());
        assertNotNull(result.getCriticalIssue());
        assertEquals("TAKE_PROFIT_MISSING", result.getCriticalIssue().getCode());
        verify(fixture.binanceClient, times(1)).submitOrder(any());
        verify(fixture.binanceClient, times(2)).submitAlgoOrder(any());
    }

    @Test
    void entrySubmitFailureIsStoredAsFailedWithoutRetryingSubmit() throws Exception {
        Fixture fixture = fixture();
        Recommendation recommendation = recommendation();
        fixture.stubRecommendation(recommendation);
        fixture.stubExecutablePreflight();

        BinanceOrderFieldsDTO entryOrder = order("BTCUSDT", "BUY", "MARKET", new BigDecimal("0.010"), null);
        BinanceOrderFieldsDTO stopLossOrder = order("BTCUSDT", "SELL", "STOP_MARKET", null, new BigDecimal("98000"));
        BinanceOrderFieldsDTO takeProfitOrder = order("BTCUSDT", "SELL", "TAKE_PROFIT_MARKET", null, new BigDecimal("101000"));
        when(fixture.exchangeExecutionPreflightService.evaluate(eq(recommendation), any()))
                .thenReturn(new ExchangeExecutionPreflightService.ExchangeExecutionPreflightResult(
                        new ExchangeExecutionPreflightService.OrderPayloads(entryOrder, stopLossOrder, takeProfitOrder),
                        new ExchangeExecutionPreflightService.OrderPayloads(entryOrder, stopLossOrder, takeProfitOrder),
                        symbolInfo(),
                        new BigDecimal("100000"),
                        new BigDecimal("0.1"),
                        new BigDecimal("0.001"),
                        new BigDecimal("0.001"),
                        new BigDecimal("5"),
                        10,
                        "ISOLATED",
                        "ONE_WAY",
                        List.of()));

        when(fixture.binanceClient.ensureOneWayPositionMode()).thenReturn(Map.of("status", "UNCHANGED"));
        when(fixture.binanceClient.ensureIsolatedMargin("BTCUSDT")).thenReturn(Map.of("status", "UNCHANGED"));
        when(fixture.binanceClient.setLeverage("BTCUSDT", 10)).thenReturn(Map.of("leverage", 10));
        when(fixture.binanceClient.submitOrder(any())).thenThrow(WebClientResponseException.create(
                400,
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"msg\":\"rejected\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
        when(fixture.diagnosticsService.classifyExecutionFailure(any()))
                .thenReturn(new LiveTradeBlockedReasonDTO(
                        LiveTradingBlockerCodes.BINANCE_REJECTED,
                        "Entry rejected by Binance.",
                        "exchange",
                        Map.of("stage", "entry")));

        LiveTradeExecutionDTO result = fixture.service.execute(
                new ApprovedExecutionCommand(
                        recommendation.getId(),
                        null,
                        null,
                        UUID.fromString("00000000-0000-0000-0000-000000000303"),
                        LiveTradeTriggerMode.MANUAL_BUTTON,
                        "operator-1",
                        "trace-failure",
                        null,
                        "entry failure"),
                null);

        assertEquals("FAILED", result.getExecutionState());
        assertEquals(LiveTradingBlockerCodes.BINANCE_REJECTED, result.getErrorCode());
        verify(fixture.binanceClient, times(1)).submitOrder(any());
        verify(fixture.binanceClient, never()).submitAlgoOrder(any());
    }

    @Test
    void safeCloseTimeoutReturnsTimedOutAndKeepsExecutionReconcilable() {
        Fixture fixture = fixture();
        LiveTradeExecution execution = existingExecution(LiveTradeExecutionState.ACTIVE);
        fixture.stubExecution(execution);

        when(fixture.orderStateSyncService.lookupPosition("BTCUSDT", "BUY"))
                .thenReturn(position("BTCUSDT", "0.010", "100000"));
        when(fixture.orderStateSyncService.positionRiskQuantity(any())).thenReturn(new BigDecimal("0.010"));
        when(fixture.binanceClient.getSymbolInfo("BTCUSDT")).thenReturn(Optional.of(symbolInfo()));
        when(fixture.binanceClient.submitOrder(any())).thenThrow(new WebClientRequestException(
                new SocketTimeoutException("timeout"),
                HttpMethod.POST,
                URI.create("https://binance.example/order"),
                HttpHeaders.EMPTY));
        when(fixture.orderStateSyncService.reconcileExecution(execution.getId(), "operator-1", "trace-timeout", false))
                .thenAnswer(invocation -> fixture.toDetail(execution.getId()));

        SafeCloseAttemptResult result = fixture.service.requestSafeClose(
                execution.getId(),
                "operator-1",
                "trace-timeout",
                "TARGET_REACHED");

        assertEquals(SafeCloseAttemptResult.SafeCloseAttemptStatus.TIMED_OUT, result.status());
        assertEquals("UPSTREAM_TIMEOUT", result.errorCode());
        assertEquals("CLOSING", result.execution().getExecutionState());
        verify(fixture.binanceClient, times(1)).submitOrder(any());
        verify(fixture.orderStateSyncService).reconcileExecution(execution.getId(), "operator-1", "trace-timeout", false);
    }

    @Test
    void safeCloseReusesExistingEmergencyCloseInsteadOfSubmittingDuplicate() {
        Fixture fixture = fixture();
        LiveTradeExecution execution = existingExecution(LiveTradeExecutionState.CLOSING);
        execution.setEmergencyCloseClientOrderId("close-client");
        execution.setEmergencyCloseOrderId(201L);
        fixture.stubExecution(execution);

        when(fixture.orderStateSyncService.lookupPosition("BTCUSDT", "BUY"))
                .thenReturn(position("BTCUSDT", "0.010", "100000"));
        when(fixture.orderStateSyncService.positionRiskQuantity(any())).thenReturn(new BigDecimal("0.010"));
        when(fixture.binanceClient.getSymbolInfo("BTCUSDT")).thenReturn(Optional.of(symbolInfo()));
        when(fixture.orderStateSyncService.reconcileExecution(execution.getId(), "operator-1", "trace-duplicate", false))
                .thenAnswer(invocation -> fixture.toDetail(execution.getId()));

        SafeCloseAttemptResult result = fixture.service.requestSafeClose(
                execution.getId(),
                "operator-1",
                "trace-duplicate",
                "TARGET_REACHED");

        assertEquals(SafeCloseAttemptResult.SafeCloseAttemptStatus.ALREADY_SUBMITTED, result.status());
        verify(fixture.binanceClient, never()).submitOrder(any());
        verify(fixture.orderStateSyncService).reconcileExecution(execution.getId(), "operator-1", "trace-duplicate", false);
    }

    @Test
    void safeCloseCancelsStaleProtectionOrdersAndResolvesFlatWithoutResubmitting() {
        Fixture fixture = fixture();
        LiveTradeExecution execution = existingExecution(LiveTradeExecutionState.CLOSING);
        execution.setSlClientOrderId("sl-client");
        execution.setSlOrderId(101L);
        execution.setTpClientOrderId("tp-client");
        execution.setTpOrderId(102L);
        fixture.stubExecution(execution);

        when(fixture.binanceClient.cancelAlgoOrder("sl-client", 101L)).thenReturn(cancelResponse(101L, "sl-client"));
        when(fixture.binanceClient.cancelAlgoOrder("tp-client", 102L)).thenReturn(cancelResponse(102L, "tp-client"));
        when(fixture.orderStateSyncService.lookupPosition("BTCUSDT", "BUY"))
                .thenReturn(position("BTCUSDT", "0", "100000"));
        when(fixture.orderStateSyncService.positionRiskQuantity(any())).thenReturn(BigDecimal.ZERO);
        when(fixture.binanceClient.getSymbolInfo("BTCUSDT")).thenReturn(Optional.of(symbolInfo()));
        when(fixture.orderStateSyncService.reconcileExecution(execution.getId(), "operator-1", "trace-flat", false))
                .thenAnswer(invocation -> {
                    execution.setExecutionState(LiveTradeExecutionState.CLOSED);
                    return fixture.toDetail(execution.getId());
                });

        SafeCloseAttemptResult result = fixture.service.requestSafeClose(
                execution.getId(),
                "operator-1",
                "trace-flat",
                "TARGET_REACHED");

        assertEquals(SafeCloseAttemptResult.SafeCloseAttemptStatus.RESOLVED_FLAT, result.status());
        assertEquals("CLOSED", result.execution().getExecutionState());
        verify(fixture.binanceClient).cancelAlgoOrder("sl-client", 101L);
        verify(fixture.binanceClient).cancelAlgoOrder("tp-client", 102L);
        verify(fixture.binanceClient, never()).submitOrder(any());
    }

    @Test
    void allocatePositionSlotHonorsLockedThreePositionCapForDirtySessionRows() throws Exception {
        Fixture fixture = fixture();
        BudgetTargetSession session = new BudgetTargetSession();
        session.setId(UUID.randomUUID());
        session.setMaxConcurrentPositions(9);

        LiveTradeExecution slotOne = existingExecution(LiveTradeExecutionState.ACTIVE);
        slotOne.setSession(session);
        slotOne.setPositionSlot(1);
        LiveTradeExecution slotTwo = existingExecution(LiveTradeExecutionState.ACTIVE);
        slotTwo.setSession(session);
        slotTwo.setPositionSlot(2);
        LiveTradeExecution slotThree = existingExecution(LiveTradeExecutionState.ACTIVE);
        slotThree.setSession(session);
        slotThree.setPositionSlot(3);

        when(fixture.executionRepository.findBySession_IdAndPositionSlotIsNotNullAndExecutionStatusInOrderByPositionSlotAsc(
                eq(session.getId()),
                any())).thenReturn(List.of(slotOne, slotTwo, slotThree));

        Method allocatePositionSlot = LiveExecutionTransactionService.class
                .getDeclaredMethod("allocatePositionSlot", BudgetTargetSession.class);
        allocatePositionSlot.setAccessible(true);

        InvocationTargetException error = org.junit.jupiter.api.Assertions.assertThrows(
                InvocationTargetException.class,
                () -> allocatePositionSlot.invoke(fixture.transactionService, session));

        assertTrue(error.getCause() instanceof LiveExecutionTransactionService.LiveExecutionPreparationException);
        assertTrue(error.getCause().getMessage().contains("locked maxConcurrentPositions=3"));
    }

    private Fixture fixture() {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        BudgetTargetSessionRepository budgetTargetSessionRepository = mock(BudgetTargetSessionRepository.class);
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        LiveTradeExecutionEventRepository eventRepository = mock(LiveTradeExecutionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        LiveTradingBinanceDiagnosticsService diagnosticsService = mock(LiveTradingBinanceDiagnosticsService.class);
        LiveTradePersistenceService persistenceService = mock(LiveTradePersistenceService.class);
        BinanceClient binanceClient = mock(BinanceClient.class);
        OrderStateSyncService orderStateSyncService = mock(OrderStateSyncService.class);
        ExchangeExecutionPreflightService exchangeExecutionPreflightService = mock(ExchangeExecutionPreflightService.class);
        BudgetTargetSessionStreamPublisher streamPublisher = mock(BudgetTargetSessionStreamPublisher.class);
        LiveExecutionTransactionService transactionService = new LiveExecutionTransactionService(
                budgetTargetSessionRepository,
                executionRepository,
                eventRepository,
                persistenceService,
                streamPublisher,
                objectMapper);

        return new Fixture(
                recommendationRepository,
                budgetTargetSessionRepository,
                executionRepository,
                eventRepository,
                preflightService,
                diagnosticsService,
                persistenceService,
                binanceClient,
                orderStateSyncService,
                exchangeExecutionPreflightService,
                transactionService,
                new LiveExecutionEngineService(
                        recommendationRepository,
                        executionRepository,
                        eventRepository,
                        preflightService,
                        diagnosticsService,
                        persistenceService,
                        transactionService,
                        binanceClient,
                        objectMapper,
                        mapper,
                        orderStateSyncService,
                        exchangeExecutionPreflightService,
                        clientOrderIdFactory,
                        responseParser,
                        roundingService,
                        streamPublisher));
    }

    private Recommendation recommendation() throws Exception {
        Recommendation recommendation = new Recommendation();
        recommendation.setId(UUID.randomUUID());
        recommendation.setSymbol("BTCUSDT");
        recommendation.setSide("BUY");
        recommendation.setCreatedAt(Instant.now());

        OrderFields orderFields = new OrderFields();
        orderFields.setMarginMode("ISOLATED");
        orderFields.setPositionMode("ONE_WAY");
        orderFields.setLeverageRecommendation(10);
        orderFields.setEntryOrderJson(objectMapper.writeValueAsString(
                order("BTCUSDT", "BUY", "MARKET", new BigDecimal("0.010"), null)));
        orderFields.setSlOrderJson(objectMapper.writeValueAsString(
                order("BTCUSDT", "SELL", "STOP_MARKET", null, new BigDecimal("98000"))));
        orderFields.setTpOrderJson(objectMapper.writeValueAsString(
                order("BTCUSDT", "SELL", "TAKE_PROFIT_MARKET", null, new BigDecimal("101000"))));
        recommendation.setOrderFields(orderFields);
        return recommendation;
    }

    private BinanceOrderFieldsDTO order(String symbol,
            String side,
            String type,
            BigDecimal quantity,
            BigDecimal stopPrice) {
        BinanceOrderFieldsDTO dto = new BinanceOrderFieldsDTO();
        dto.setSymbol(symbol);
        dto.setSide(side);
        dto.setType(type);
        dto.setQuantity(quantity);
        dto.setStopPrice(stopPrice);
        dto.setClosePosition(true);
        dto.setWorkingType("MARK_PRICE");
        return dto;
    }

    private BinanceExchangeInfoResponse.SymbolInfo symbolInfo() {
        BinanceExchangeInfoResponse.SymbolInfo symbolInfo = new BinanceExchangeInfoResponse.SymbolInfo();
        symbolInfo.setSymbol("BTCUSDT");
        symbolInfo.setStatus("TRADING");
        symbolInfo.setContractType("PERPETUAL");
        symbolInfo.setQuoteAsset("USDT");

        BinanceExchangeInfoResponse.Filter priceFilter = new BinanceExchangeInfoResponse.Filter();
        priceFilter.setFilterType("PRICE_FILTER");
        priceFilter.setTickSize("0.1");

        BinanceExchangeInfoResponse.Filter marketLotSize = new BinanceExchangeInfoResponse.Filter();
        marketLotSize.setFilterType("MARKET_LOT_SIZE");
        marketLotSize.setStepSize("0.001");
        marketLotSize.setMinQty("0.001");

        BinanceExchangeInfoResponse.Filter notionalFilter = new BinanceExchangeInfoResponse.Filter();
        notionalFilter.setFilterType("MIN_NOTIONAL");
        notionalFilter.setMinNotional("5");

        symbolInfo.setFilters(List.of(priceFilter, marketLotSize, notionalFilter));
        return symbolInfo;
    }

    private BinanceFuturesOrderResponse entryResponse(String status,
            Long orderId,
            String executedQty,
            String avgPrice) {
        BinanceFuturesOrderResponse response = new BinanceFuturesOrderResponse();
        response.setStatus(status);
        response.setOrderId(orderId);
        response.setClientOrderId("entry-client");
        response.setExecutedQty(executedQty);
        response.setAvgPrice(avgPrice);
        return response;
    }

    private BinanceFuturesAlgoOrderResponse algoResponse(Long algoId, String clientAlgoId, String algoStatus) {
        BinanceFuturesAlgoOrderResponse response = new BinanceFuturesAlgoOrderResponse();
        response.setAlgoId(algoId);
        response.setClientAlgoId(clientAlgoId);
        response.setAlgoStatus(algoStatus);
        response.setOrderType("STOP_MARKET");
        response.setTriggerPrice("98000");
        return response;
    }

    private BinanceFuturesAlgoOrderCancelResponse cancelResponse(Long algoId, String clientAlgoId) {
        BinanceFuturesAlgoOrderCancelResponse response = new BinanceFuturesAlgoOrderCancelResponse();
        response.setAlgoId(algoId);
        response.setClientAlgoId(clientAlgoId);
        response.setCode("0");
        response.setMsg("success");
        return response;
    }

    private final class Fixture {
        private final RecommendationRepository recommendationRepository;
        private final BudgetTargetSessionRepository budgetTargetSessionRepository;
        private final LiveTradeExecutionRepository executionRepository;
        private final LiveTradeExecutionEventRepository eventRepository;
        private final LiveTradingPreflightService preflightService;
        private final LiveTradingBinanceDiagnosticsService diagnosticsService;
        private final LiveTradePersistenceService persistenceService;
        private final BinanceClient binanceClient;
        private final OrderStateSyncService orderStateSyncService;
        private final ExchangeExecutionPreflightService exchangeExecutionPreflightService;
        private final LiveExecutionTransactionService transactionService;
        private final LiveExecutionEngineService service;
        private final Map<UUID, LiveTradeExecution> executions = new LinkedHashMap<>();
        private final List<LiveTradeExecutionEvent> events = new ArrayList<>();

        private Fixture(RecommendationRepository recommendationRepository,
                BudgetTargetSessionRepository budgetTargetSessionRepository,
                LiveTradeExecutionRepository executionRepository,
                LiveTradeExecutionEventRepository eventRepository,
                LiveTradingPreflightService preflightService,
                LiveTradingBinanceDiagnosticsService diagnosticsService,
                LiveTradePersistenceService persistenceService,
                BinanceClient binanceClient,
                OrderStateSyncService orderStateSyncService,
                ExchangeExecutionPreflightService exchangeExecutionPreflightService,
                LiveExecutionTransactionService transactionService,
                LiveExecutionEngineService service) {
            this.recommendationRepository = recommendationRepository;
            this.budgetTargetSessionRepository = budgetTargetSessionRepository;
            this.executionRepository = executionRepository;
            this.eventRepository = eventRepository;
            this.preflightService = preflightService;
            this.diagnosticsService = diagnosticsService;
            this.persistenceService = persistenceService;
            this.binanceClient = binanceClient;
            this.orderStateSyncService = orderStateSyncService;
            this.exchangeExecutionPreflightService = exchangeExecutionPreflightService;
            this.transactionService = transactionService;
            this.service = service;

            when(executionRepository.save(any())).thenAnswer(invocation -> {
                LiveTradeExecution execution = invocation.getArgument(0);
                if (execution.getId() == null) {
                    execution.setId(UUID.randomUUID());
                }
                if (execution.getCreatedAt() == null) {
                    execution.setCreatedAt(Instant.now());
                }
                execution.setUpdatedAt(Instant.now());
                executions.put(execution.getId(), execution);
                return execution;
            });
            when(executionRepository.findById(any())).thenAnswer(invocation ->
                    Optional.ofNullable(executions.get(invocation.getArgument(0))));
            when(executionRepository.findByIdForUpdate(any())).thenAnswer(invocation ->
                    Optional.ofNullable(executions.get(invocation.getArgument(0))));
            when(eventRepository.save(any())).thenAnswer(invocation -> {
                LiveTradeExecutionEvent event = invocation.getArgument(0);
                if (event.getId() == null) {
                    event.setId(UUID.randomUUID());
                }
                events.add(event);
                return event;
            });
            when(eventRepository.findByExecution_IdOrderByCreatedAtAsc(any())).thenAnswer(invocation -> {
                UUID executionId = invocation.getArgument(0);
                return events.stream()
                        .filter(event -> event.getExecution() != null
                                && executionId.equals(event.getExecution().getId()))
                        .toList();
            });
        }

        private void stubRecommendation(Recommendation recommendation) {
            when(recommendationRepository.findDetailedById(recommendation.getId())).thenReturn(Optional.of(recommendation));
            when(executionRepository.findFirstByRecommendation_IdAndClientRequestId(eq(recommendation.getId()), any()))
                    .thenReturn(Optional.empty());
        }

        private void stubExecution(LiveTradeExecution execution) {
            executions.put(execution.getId(), execution);
            when(executionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
            when(eventRepository.findByExecution_IdOrderByCreatedAtAsc(execution.getId())).thenAnswer(invocation ->
                    events.stream()
                            .filter(event -> event.getExecution() != null
                                    && execution.getId().equals(event.getExecution().getId()))
                            .toList());
        }

        private void stubExecutablePreflight() {
            LiveTradingPreflightDTO preflight = new LiveTradingPreflightDTO();
            preflight.setAllowed(true);
            preflight.setExecutable(true);
            preflight.getExchangeValidation().setEntryNotionalUsdt(new BigDecimal("100"));
            preflight.getExchangeValidation().setLeverage(10);
            when(preflightService.evaluate(any(), any(), eq(null))).thenReturn(preflight);
            doAnswer(invocation -> null).when(exchangeExecutionPreflightService).applyTo(any(), any());
        }

        private void stubExisting(LiveTradeExecution execution) {
            stubExecution(execution);
            executions.put(execution.getId(), execution);
            when(executionRepository.findFirstByRecommendation_IdAndClientRequestId(
                    execution.getRecommendation().getId(),
                    execution.getClientRequestId())).thenReturn(Optional.of(execution));
            when(eventRepository.findByExecution_IdOrderByCreatedAtAsc(execution.getId())).thenReturn(events);
        }

        private LiveTradeExecutionDTO toDetail(UUID executionId) {
            return mapper.toDetail(executions.get(executionId), events.stream()
                    .filter(event -> event.getExecution() != null
                            && executionId.equals(event.getExecution().getId()))
                    .toList());
        }
    }

    private LiveTradeExecution existingExecution(LiveTradeExecutionState state) {
        Recommendation recommendation = new Recommendation();
        recommendation.setId(UUID.randomUUID());
        recommendation.setSymbol("BTCUSDT");
        recommendation.setSide("BUY");
        recommendation.setCreatedAt(Instant.now());
        recommendation.setOrderFields(new OrderFields());

        LiveTradeExecution execution = new LiveTradeExecution();
        execution.setId(UUID.randomUUID());
        execution.setRecommendation(recommendation);
        execution.setTriggerMode(LiveTradeTriggerMode.AUTO_SESSION);
        execution.setSymbol("BTCUSDT");
        execution.setSide("BUY");
        execution.setOperatorId("operator-1");
        execution.setTraceId("trace-" + execution.getId());
        execution.setClientRequestId(UUID.randomUUID());
        execution.setExecutionState(state);
        execution.setActualFilledQty(new BigDecimal("0.010"));
        execution.setCreatedAt(Instant.now().minusSeconds(30));
        execution.setUpdatedAt(Instant.now().minusSeconds(5));
        return execution;
    }

    private com.tradebot.dto.BinanceFuturesPositionRiskResponse position(String symbol, String positionAmt, String entryPrice) {
        com.tradebot.dto.BinanceFuturesPositionRiskResponse response = new com.tradebot.dto.BinanceFuturesPositionRiskResponse();
        response.setSymbol(symbol);
        response.setPositionSide("BOTH");
        response.setPositionAmt(positionAmt);
        response.setEntryPrice(entryPrice);
        return response;
    }
}
