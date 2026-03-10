package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
import com.tradebot.dto.BinanceFuturesAlgoOrderResponse;
import com.tradebot.dto.BinanceFuturesOrderResponse;
import com.tradebot.dto.BinanceFuturesPositionRiskResponse;
import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionEvent;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.entity.LiveTradeTriggerMode;
import com.tradebot.entity.Recommendation;
import com.tradebot.repository.LiveTradeExecutionEventRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.service.BinanceExecutionResponseParser;
import com.tradebot.service.BudgetTargetAutoExecutionLifecycleService;
import com.tradebot.service.BudgetTargetSessionStreamPublisher;
import com.tradebot.service.ExchangeSyncSnapshotService;
import com.tradebot.service.LiveTradePersistenceService;
import com.tradebot.service.LiveTradingMapper;
import com.tradebot.service.OrderStateSyncService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderStateSyncServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LiveTradingMapper mapper = new LiveTradingMapper(objectMapper);
    private final BinanceExecutionResponseParser responseParser = new BinanceExecutionResponseParser();

    @Test
    void reconcileMarksActiveAndRequiresInterventionWhenTakeProfitIsMissing() {
        Fixture fixture = fixture();
        LiveTradeExecution execution = execution(LiveTradeExecutionState.PROTECTION_SUBMITTING);
        execution.setExchangeResponseJson("{}");
        execution.setSlClientOrderId("sl-client");
        execution.setSlOrderId(101L);
        execution.setTpClientOrderId("tp-client");
        execution.setTpOrderId(102L);
        fixture.stubExecution(execution);

        when(fixture.binanceClient.getOrder("BTCUSDT", "entry-client", 11L))
                .thenReturn(order("FILLED", 11L, "entry-client", "0.010", "100000"));
        when(fixture.binanceClient.getOpenAlgoOrders("BTCUSDT", "CONDITIONAL", null))
                .thenReturn(List.of(algo(101L, "sl-client", "NEW", null)));
        when(fixture.binanceClient.getAlgoOrder("sl-client", 101L))
                .thenReturn(algo(101L, "sl-client", "NEW", null));
        when(fixture.binanceClient.getAlgoOrder("tp-client", 102L))
                .thenReturn(null);
        when(fixture.binanceClient.getPositionRisk("BTCUSDT"))
                .thenReturn(List.of(position("BTCUSDT", "0.010", "100000")));

        LiveTradeExecutionDTO result = fixture.service.reconcileExecution(execution.getId(), "tester", "trace-1", false);

        assertEquals("ACTIVE", result.getExecutionState());
        assertTrue(result.isRequiresIntervention());
        assertNotNull(result.getCriticalIssue());
        assertEquals("TAKE_PROFIT_MISSING", result.getCriticalIssue().getCode());
    }

    @Test
    void reconcileMarksClosingWhenStopLossMissingButEmergencyCloseIsWorking() {
        Fixture fixture = fixture();
        LiveTradeExecution execution = execution(LiveTradeExecutionState.RECONCILING);
        execution.setExchangeResponseJson("{}");
        execution.setTpClientOrderId("tp-client");
        execution.setTpOrderId(102L);
        execution.setEmergencyCloseClientOrderId("close-client");
        execution.setEmergencyCloseOrderId(201L);
        fixture.stubExecution(execution);

        when(fixture.binanceClient.getOrder("BTCUSDT", "entry-client", 11L))
                .thenReturn(order("FILLED", 11L, "entry-client", "0.010", "100000"));
        when(fixture.binanceClient.getOrder("BTCUSDT", "close-client", 201L))
                .thenReturn(order("NEW", 201L, "close-client", "0.000", "0"));
        when(fixture.binanceClient.getOpenAlgoOrders("BTCUSDT", "CONDITIONAL", null))
                .thenReturn(List.of());
        when(fixture.binanceClient.getAlgoOrder("tp-client", 102L))
                .thenReturn(null);
        when(fixture.binanceClient.getPositionRisk("BTCUSDT"))
                .thenReturn(List.of(position("BTCUSDT", "0.010", "100000")));

        LiveTradeExecutionDTO result = fixture.service.reconcileExecution(execution.getId(), "tester", "trace-2", false);

        assertEquals("CLOSING", result.getExecutionState());
        assertTrue(result.isRequiresIntervention());
        assertEquals("STOP_LOSS_MISSING", result.getCriticalIssue().getCode());
    }

    @Test
    void reconcileMarksClosedAndPersistsClosureRollups() {
        Fixture fixture = fixture();
        LiveTradeExecution execution = execution(LiveTradeExecutionState.CLOSING);
        execution.setExchangeResponseJson("{}");
        execution.setActualFilledQty(new BigDecimal("0.010"));
        execution.setEmergencyCloseClientOrderId("close-client");
        execution.setEmergencyCloseOrderId(201L);
        execution.setSubmittedAt(Instant.now().minusSeconds(60));
        execution.setCompletedAt(Instant.now().minusSeconds(5));
        BudgetTargetSession session = new BudgetTargetSession();
        session.setId(UUID.randomUUID());
        execution.setSession(session);
        fixture.stubExecution(execution);

        when(fixture.binanceClient.getOrder("BTCUSDT", "entry-client", 11L))
                .thenReturn(order("FILLED", 11L, "entry-client", "0.010", "100000"));
        when(fixture.binanceClient.getOrder("BTCUSDT", "close-client", 201L))
                .thenReturn(order("FILLED", 201L, "close-client", "0.010", "99950"));
        when(fixture.binanceClient.getOpenAlgoOrders("BTCUSDT", "CONDITIONAL", null))
                .thenReturn(List.of());
        when(fixture.binanceClient.getPositionRisk("BTCUSDT"))
                .thenReturn(List.of(position("BTCUSDT", "0", "100000")));
        when(fixture.binanceClient.getUserTrades("BTCUSDT", 11L, null, null))
                .thenReturn(List.of(Map.of("realizedPnl", "0.80", "commission", "0.04")));
        when(fixture.binanceClient.getUserTrades("BTCUSDT", 201L, null, null))
                .thenReturn(List.of(Map.of("realizedPnl", "0.20", "commission", "0.03")));

        LiveTradeExecutionDTO result = fixture.service.reconcileExecution(execution.getId(), "tester", "trace-3", false);

        assertEquals("CLOSED", result.getExecutionState());
        verify(fixture.liveTradePersistenceService, times(1))
                .upsertClosure(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.liveTradePersistenceService, times(2))
                .upsertLedgerEntry(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.liveTradePersistenceService, times(1)).refreshExecutionNetPnl(execution);
        verify(fixture.liveTradePersistenceService, times(1))
                .refreshSessionRollup(eq(session), any());
        verify(fixture.exchangeSyncSnapshotService, times(1)).persistSnapshot(any());
    }

    @Test
    void reconcileFailsClosedWhenRealizedPnlCannotBeResolvedFromBinanceTruth() {
        Fixture fixture = fixture();
        LiveTradeExecution execution = execution(LiveTradeExecutionState.CLOSING);
        execution.setExchangeResponseJson("{}");
        execution.setActualFilledQty(new BigDecimal("0.010"));
        execution.setEmergencyCloseClientOrderId("close-client");
        execution.setEmergencyCloseOrderId(201L);
        execution.setSubmittedAt(Instant.now().minusSeconds(60));
        execution.setCompletedAt(Instant.now().minusSeconds(5));
        BudgetTargetSession session = new BudgetTargetSession();
        session.setId(UUID.randomUUID());
        execution.setSession(session);
        fixture.stubExecution(execution);

        when(fixture.binanceClient.getOrder("BTCUSDT", "entry-client", 11L))
                .thenReturn(order("FILLED", 11L, "entry-client", "0.010", "100000"));
        when(fixture.binanceClient.getOrder("BTCUSDT", "close-client", 201L))
                .thenReturn(order("FILLED", 201L, "close-client", "0.010", "99950"));
        when(fixture.binanceClient.getOpenAlgoOrders("BTCUSDT", "CONDITIONAL", null))
                .thenReturn(List.of());
        when(fixture.binanceClient.getPositionRisk("BTCUSDT"))
                .thenReturn(List.of(position("BTCUSDT", "0", "100000")));
        when(fixture.binanceClient.getUserTrades("BTCUSDT", 11L, null, null)).thenReturn(List.of());
        when(fixture.binanceClient.getUserTrades("BTCUSDT", 201L, null, null)).thenReturn(List.of());
        when(fixture.binanceClient.getIncomeHistory(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        LiveTradeExecutionDTO result = fixture.service.reconcileExecution(execution.getId(), "tester", "trace-3b", false);

        assertEquals("CLOSED", result.getExecutionState());
        assertEquals("REALIZED_PNL_UNRESOLVED", result.getErrorCode());
        assertTrue(result.isRequiresIntervention());
        verify(fixture.liveTradePersistenceService, times(1))
                .upsertClosure(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.liveTradePersistenceService, times(0))
                .upsertLedgerEntry(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.lifecycleService).requestStop(
                eq(session),
                eq(com.tradebot.entity.BudgetTargetSessionCompletionReason.FATAL_SYNC_ERROR),
                eq("system"),
                any());
    }

    @Test
    void reconcileAutoHealsSessionOwnedActiveExecutionWhenExchangeReportsFlat() {
        Fixture fixture = fixture();
        LiveTradeExecution execution = execution(LiveTradeExecutionState.ACTIVE);
        execution.setExchangeResponseJson("{}");
        execution.setActualFilledQty(new BigDecimal("0.010"));
        BudgetTargetSession session = new BudgetTargetSession();
        session.setId(UUID.randomUUID());
        execution.setSession(session);
        fixture.stubExecution(execution);

        when(fixture.binanceClient.getOrder("BTCUSDT", "entry-client", 11L))
                .thenReturn(order("FILLED", 11L, "entry-client", "0.010", "100000"));
        when(fixture.binanceClient.getOpenAlgoOrders("BTCUSDT", "CONDITIONAL", null))
                .thenReturn(List.of());
        when(fixture.binanceClient.getPositionRisk("BTCUSDT"))
                .thenReturn(List.of(position("BTCUSDT", "0", "100000")));

        LiveTradeExecutionDTO result = fixture.service.reconcileExecution(execution.getId(), "tester", "trace-4", false);

        assertEquals("CLOSED", result.getExecutionState());
        verify(fixture.lifecycleService).appendSessionEvent(
                eq(session),
                eq(execution),
                eq("EXECUTION_AUTO_HEALED"),
                any(),
                eq("EXCHANGE_POSITION_FLAT"),
                any());
    }

    @Test
    void reconcileClosesClosingExecutionEvenIfProtectionOrdersStillExistButPositionIsFlat() {
        Fixture fixture = fixture();
        LiveTradeExecution execution = execution(LiveTradeExecutionState.CLOSING);
        execution.setExchangeResponseJson("{}");
        execution.setActualFilledQty(new BigDecimal("0.010"));
        execution.setSlClientOrderId("sl-client");
        execution.setSlOrderId(101L);
        execution.setTpClientOrderId("tp-client");
        execution.setTpOrderId(102L);
        fixture.stubExecution(execution);

        when(fixture.binanceClient.getOrder("BTCUSDT", "entry-client", 11L))
                .thenReturn(order("FILLED", 11L, "entry-client", "0.010", "100000"));
        when(fixture.binanceClient.getOpenAlgoOrders("BTCUSDT", "CONDITIONAL", null))
                .thenReturn(List.of(
                        algo(101L, "sl-client", "NEW", null),
                        algo(102L, "tp-client", "NEW", null)));
        when(fixture.binanceClient.getAlgoOrder("sl-client", 101L))
                .thenReturn(algo(101L, "sl-client", "NEW", null));
        when(fixture.binanceClient.getAlgoOrder("tp-client", 102L))
                .thenReturn(algo(102L, "tp-client", "NEW", null));
        when(fixture.binanceClient.getPositionRisk("BTCUSDT"))
                .thenReturn(List.of(position("BTCUSDT", "0", "100000")));

        LiveTradeExecutionDTO result = fixture.service.reconcileExecution(execution.getId(), "tester", "trace-5", false);

        assertEquals("CLOSED", result.getExecutionState());
        verify(fixture.liveTradePersistenceService).upsertClosure(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private Fixture fixture() {
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        LiveTradeExecutionEventRepository eventRepository = mock(LiveTradeExecutionEventRepository.class);
        BinanceClient binanceClient = mock(BinanceClient.class);
        LiveTradePersistenceService liveTradePersistenceService = mock(LiveTradePersistenceService.class);
        BudgetTargetAutoExecutionLifecycleService lifecycleService = mock(BudgetTargetAutoExecutionLifecycleService.class);
        BudgetTargetSessionStreamPublisher streamPublisher = mock(BudgetTargetSessionStreamPublisher.class);
        ExchangeSyncSnapshotService exchangeSyncSnapshotService = mock(ExchangeSyncSnapshotService.class);
        when(lifecycleService.setVisibleFailure(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lifecycleService.requestStop(any(), any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(exchangeSyncSnapshotService.summarizeExecution(any())).thenReturn(new com.tradebot.dto.BudgetTargetSyncHealthDTO());
        when(exchangeSyncSnapshotService.persistSnapshot(any())).thenReturn(null);
        return new Fixture(
                executionRepository,
                eventRepository,
                binanceClient,
                liveTradePersistenceService,
                lifecycleService,
                exchangeSyncSnapshotService,
                new OrderStateSyncService(
                        executionRepository,
                        eventRepository,
                        binanceClient,
                        mapper,
                        liveTradePersistenceService,
                        lifecycleService,
                        objectMapper,
                        responseParser,
                        streamPublisher,
                        exchangeSyncSnapshotService));
    }

    private LiveTradeExecution execution(LiveTradeExecutionState state) {
        Recommendation recommendation = new Recommendation();
        recommendation.setId(UUID.randomUUID());

        LiveTradeExecution execution = new LiveTradeExecution();
        execution.setId(UUID.randomUUID());
        execution.setRecommendation(recommendation);
        execution.setTriggerMode(LiveTradeTriggerMode.MANUAL_BUTTON);
        execution.setSymbol("BTCUSDT");
        execution.setSide("BUY");
        execution.setEntryClientOrderId("entry-client");
        execution.setEntryOrderId(11L);
        execution.setExecutionState(state);
        execution.setCreatedAt(Instant.now());
        execution.setUpdatedAt(Instant.now());
        return execution;
    }

    private BinanceFuturesOrderResponse order(String status,
            Long orderId,
            String clientOrderId,
            String executedQty,
            String avgPrice) {
        BinanceFuturesOrderResponse order = new BinanceFuturesOrderResponse();
        order.setStatus(status);
        order.setOrderId(orderId);
        order.setClientOrderId(clientOrderId);
        order.setExecutedQty(executedQty);
        order.setAvgPrice(avgPrice);
        return order;
    }

    private BinanceFuturesAlgoOrderResponse algo(Long algoId,
            String clientAlgoId,
            String algoStatus,
            Long actualOrderId) {
        BinanceFuturesAlgoOrderResponse algo = new BinanceFuturesAlgoOrderResponse();
        algo.setAlgoId(algoId);
        algo.setClientAlgoId(clientAlgoId);
        algo.setAlgoStatus(algoStatus);
        algo.setOrderType("STOP_MARKET");
        algo.setTriggerPrice("98000.0");
        algo.setActualOrderId(actualOrderId == null ? null : String.valueOf(actualOrderId));
        return algo;
    }

    private BinanceFuturesPositionRiskResponse position(String symbol, String positionAmt, String entryPrice) {
        BinanceFuturesPositionRiskResponse response = new BinanceFuturesPositionRiskResponse();
        response.setSymbol(symbol);
        response.setPositionSide("BOTH");
        response.setPositionAmt(positionAmt);
        response.setEntryPrice(entryPrice);
        return response;
    }

    private static final class Fixture {
        private final LiveTradeExecutionRepository executionRepository;
        private final LiveTradeExecutionEventRepository eventRepository;
        private final BinanceClient binanceClient;
        private final LiveTradePersistenceService liveTradePersistenceService;
        private final BudgetTargetAutoExecutionLifecycleService lifecycleService;
        private final ExchangeSyncSnapshotService exchangeSyncSnapshotService;
        private final OrderStateSyncService service;
        private final LinkedHashMap<UUID, LiveTradeExecution> executions = new LinkedHashMap<>();
        private final List<LiveTradeExecutionEvent> events = new ArrayList<>();

        private Fixture(LiveTradeExecutionRepository executionRepository,
                LiveTradeExecutionEventRepository eventRepository,
                BinanceClient binanceClient,
                LiveTradePersistenceService liveTradePersistenceService,
                BudgetTargetAutoExecutionLifecycleService lifecycleService,
                ExchangeSyncSnapshotService exchangeSyncSnapshotService,
                OrderStateSyncService service) {
            this.executionRepository = executionRepository;
            this.eventRepository = eventRepository;
            this.binanceClient = binanceClient;
            this.liveTradePersistenceService = liveTradePersistenceService;
            this.lifecycleService = lifecycleService;
            this.exchangeSyncSnapshotService = exchangeSyncSnapshotService;
            this.service = service;
        }

        private void stubExecution(LiveTradeExecution execution) {
            executions.put(execution.getId(), execution);
            when(executionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
            when(executionRepository.save(any())).thenAnswer(invocation -> {
                LiveTradeExecution saved = invocation.getArgument(0);
                executions.put(saved.getId(), saved);
                return saved;
            });
            when(eventRepository.save(any())).thenAnswer(invocation -> {
                LiveTradeExecutionEvent event = invocation.getArgument(0);
                if (event.getId() == null) {
                    event.setId(UUID.randomUUID());
                }
                events.add(event);
                return event;
            });
            when(eventRepository.findByExecution_IdOrderByCreatedAtAsc(execution.getId())).thenAnswer(invocation ->
                    events.stream()
                            .filter(event -> event.getExecution() != null
                                    && execution.getId().equals(event.getExecution().getId()))
                            .toList());
        }
    }
}
