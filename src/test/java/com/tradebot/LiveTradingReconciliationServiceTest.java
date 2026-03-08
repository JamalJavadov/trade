package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
import com.tradebot.dto.BinanceFuturesAlgoOrderResponse;
import com.tradebot.dto.BinanceFuturesOrderResponse;
import com.tradebot.dto.BinanceFuturesPositionRiskResponse;
import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionEvent;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.entity.LiveTradeTriggerMode;
import com.tradebot.entity.Recommendation;
import com.tradebot.repository.LiveTradeExecutionEventRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.service.LiveTradingMapper;
import com.tradebot.service.LiveTradingReconciliationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveTradingReconciliationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void reconcileMarksProtectionActiveWhenBothAlgoLegsRemainOpen() {
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        LiveTradeExecutionEventRepository eventRepository = mock(LiveTradeExecutionEventRepository.class);
        BinanceClient binanceClient = mock(BinanceClient.class);

        LiveTradeExecution execution = execution("PROTECTION_SUBMITTING");
        execution.setSlClientOrderId("sl-client");
        execution.setSlOrderId(101L);
        execution.setTpClientOrderId("tp-client");
        execution.setTpOrderId(102L);
        execution.setExchangeResponseJson("{}");

        LinkedHashMap<UUID, LiveTradeExecution> executions = new LinkedHashMap<>();
        List<LiveTradeExecutionEvent> events = new ArrayList<>();
        stubPersistence(executionRepository, eventRepository, executions, events, execution);

        when(binanceClient.getOrder("BTCUSDT", execution.getEntryClientOrderId(), execution.getEntryOrderId()))
                .thenReturn(order("FILLED", 11L, "0.010"));
        when(binanceClient.getOpenAlgoOrders("BTCUSDT", "CONDITIONAL", null))
                .thenReturn(List.of(algo(101L, "sl-client", "NEW"), algo(102L, "tp-client", "NEW")));
        when(binanceClient.getAlgoOrder("sl-client", 101L)).thenReturn(algo(101L, "sl-client", "NEW"));
        when(binanceClient.getAlgoOrder("tp-client", 102L)).thenReturn(algo(102L, "tp-client", "NEW"));
        when(binanceClient.getPositionRisk("BTCUSDT"))
                .thenReturn(List.of(position("BTCUSDT", "0.010", "100000.0")));

        LiveTradingReconciliationService service = new LiveTradingReconciliationService(
                executionRepository,
                eventRepository,
                binanceClient,
                new LiveTradingMapper(objectMapper),
                objectMapper);

        LiveTradeExecutionDTO result = service.reconcileExecution(execution.getId(), "tester", "trace-1", false);

        assertEquals("PROTECTION_ACTIVE", result.getExecutionState());
    }

    @Test
    void reconcileMarksEmergencyCloseFilledAndPreservesProtectionError() {
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        LiveTradeExecutionEventRepository eventRepository = mock(LiveTradeExecutionEventRepository.class);
        BinanceClient binanceClient = mock(BinanceClient.class);

        LiveTradeExecution execution = execution("EMERGENCY_CLOSE_SUBMITTED");
        execution.setErrorCode("BINANCE_REJECTED");
        execution.setErrorMessage("Stop-loss protection failed.");
        execution.setEmergencyCloseClientOrderId("close-client");
        execution.setEmergencyCloseOrderId(22L);
        execution.setExchangeResponseJson("{}");

        LinkedHashMap<UUID, LiveTradeExecution> executions = new LinkedHashMap<>();
        List<LiveTradeExecutionEvent> events = new ArrayList<>();
        stubPersistence(executionRepository, eventRepository, executions, events, execution);

        when(binanceClient.getOrder("BTCUSDT", execution.getEntryClientOrderId(), execution.getEntryOrderId()))
                .thenReturn(order("FILLED", 11L, "0.010"));
        when(binanceClient.getOrder("BTCUSDT", execution.getEmergencyCloseClientOrderId(), execution.getEmergencyCloseOrderId()))
                .thenReturn(order("FILLED", 22L, "0.010"));
        when(binanceClient.getOpenAlgoOrders("BTCUSDT", "CONDITIONAL", null)).thenReturn(List.of());
        when(binanceClient.getPositionRisk("BTCUSDT"))
                .thenReturn(List.of(position("BTCUSDT", "0", "100000.0")));

        LiveTradingReconciliationService service = new LiveTradingReconciliationService(
                executionRepository,
                eventRepository,
                binanceClient,
                new LiveTradingMapper(objectMapper),
                objectMapper);

        LiveTradeExecutionDTO result = service.reconcileExecution(execution.getId(), "tester", "trace-2", false);

        assertEquals("EMERGENCY_CLOSE_FILLED", result.getExecutionState());
        assertEquals("BINANCE_REJECTED", result.getErrorCode());
        assertEquals("Stop-loss protection failed.", result.getErrorMessage());
    }

    private void stubPersistence(LiveTradeExecutionRepository executionRepository,
            LiveTradeExecutionEventRepository eventRepository,
            LinkedHashMap<UUID, LiveTradeExecution> executions,
            List<LiveTradeExecutionEvent> events,
            LiveTradeExecution execution) {
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
        when(eventRepository.findByExecution_IdOrderByCreatedAtAsc(eq(execution.getId()))).thenAnswer(invocation ->
                events.stream()
                        .filter(event -> event.getExecution() != null && execution.getId().equals(event.getExecution().getId()))
                        .toList());
    }

    private LiveTradeExecution execution(String state) {
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
        execution.setExecutionState(LiveTradeExecutionState.valueOf(state));
        execution.setCreatedAt(Instant.now());
        execution.setUpdatedAt(Instant.now());
        return execution;
    }

    private BinanceFuturesOrderResponse order(String status, Long orderId, String executedQty) {
        BinanceFuturesOrderResponse order = new BinanceFuturesOrderResponse();
        order.setOrderId(orderId);
        order.setClientOrderId("client-" + orderId);
        order.setStatus(status);
        order.setExecutedQty(executedQty);
        return order;
    }

    private BinanceFuturesAlgoOrderResponse algo(Long algoId, String clientAlgoId, String algoStatus) {
        BinanceFuturesAlgoOrderResponse algo = new BinanceFuturesAlgoOrderResponse();
        algo.setAlgoId(algoId);
        algo.setClientAlgoId(clientAlgoId);
        algo.setAlgoStatus(algoStatus);
        algo.setOrderType("STOP_MARKET");
        algo.setTriggerPrice("98000.0");
        return algo;
    }

    private BinanceFuturesPositionRiskResponse position(String symbol, String qty, String entryPrice) {
        BinanceFuturesPositionRiskResponse response = new BinanceFuturesPositionRiskResponse();
        response.setSymbol(symbol);
        response.setPositionSide("BOTH");
        response.setPositionAmt(qty);
        response.setEntryPrice(entryPrice);
        return response;
    }
}
