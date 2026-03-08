package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
import com.tradebot.dto.BinanceExchangeInfoResponse;
import com.tradebot.dto.BinanceFuturesAlgoOrderResponse;
import com.tradebot.dto.BinanceFuturesOrderResponse;
import com.tradebot.dto.BinanceFuturesPositionRiskResponse;
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
import com.tradebot.entity.ScanRun;
import com.tradebot.repository.LiveTradeExecutionEventRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.service.LiveTradingBinanceDiagnosticsService;
import com.tradebot.service.LiveTradingBlockerCodes;
import com.tradebot.service.LiveTradingExecutionService;
import com.tradebot.service.LiveTradingMapper;
import com.tradebot.service.LiveTradingPreflightService;
import com.tradebot.service.LiveTradingReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class LiveTradingExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void blockedPreflightPersistsWithoutCallingBinance() {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        LiveTradeExecutionEventRepository eventRepository = mock(LiveTradeExecutionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        LiveTradingReconciliationService reconciliationService = mock(LiveTradingReconciliationService.class);
        LiveTradingBinanceDiagnosticsService diagnosticsService = mock(LiveTradingBinanceDiagnosticsService.class);
        BinanceClient binanceClient = mock(BinanceClient.class);

        Recommendation recommendation = recommendation();
        LinkedHashMap<UUID, LiveTradeExecution> executions = new LinkedHashMap<>();
        List<LiveTradeExecutionEvent> events = new ArrayList<>();

        UUID clientRequestId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(executionRepository.findFirstByRecommendation_IdAndClientRequestId(recommendation.getId(), clientRequestId))
                .thenReturn(Optional.empty());
        when(recommendationRepository.findById(recommendation.getId())).thenReturn(Optional.of(recommendation));
        when(executionRepository.save(any())).thenAnswer(invocation -> {
            LiveTradeExecution execution = invocation.getArgument(0);
            if (execution.getId() == null) {
                execution.setId(UUID.randomUUID());
            }
            executions.put(execution.getId(), execution);
            return execution;
        });
        when(executionRepository.findById(any())).thenAnswer(invocation ->
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
                    .filter(event -> event.getExecution() != null && executionId.equals(event.getExecution().getId()))
                    .toList();
        });

        LiveTradingPreflightDTO preflight = new LiveTradingPreflightDTO();
        preflight.setExecutable(false);
        preflight.setAllowed(false);
        preflight.getBlockedReasons().add(new LiveTradeBlockedReasonDTO(
                LiveTradingBlockerCodes.BOT_READ_ONLY,
                "Trading is disabled. Bot is in READ-ONLY mode.",
                "runtime",
                java.util.Map.of()));
        when(preflightService.evaluate(eq(recommendation.getId()), any(UUID.class), isNull())).thenReturn(preflight);

        LiveTradingExecutionService service = new LiveTradingExecutionService(
                recommendationRepository,
                executionRepository,
                eventRepository,
                preflightService,
                reconciliationService,
                new LiveTradingMapper(objectMapper),
                diagnosticsService,
                binanceClient,
                objectMapper);

        LiveTradeExecutionRequestDTO request = new LiveTradeExecutionRequestDTO();
        request.setClientRequestId(clientRequestId);

        LiveTradeExecutionDTO result = service.executeLive(recommendation.getId(), request, null, "trace-1", null);

        assertEquals("BLOCKED", result.getExecutionState());
        assertFalse(result.isDryRun());
        assertEquals(LiveTradingBlockerCodes.BOT_READ_ONLY, result.getErrorCode());
        verifyNoInteractions(binanceClient);
        verify(reconciliationService, never()).reconcileExecution(any(), any(), any(), any(Boolean.class));
    }

    @Test
    void duplicateClientRequestIdReturnsExistingAttemptWithoutResubmitting() {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        LiveTradeExecutionEventRepository eventRepository = mock(LiveTradeExecutionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        LiveTradingReconciliationService reconciliationService = mock(LiveTradingReconciliationService.class);
        LiveTradingBinanceDiagnosticsService diagnosticsService = mock(LiveTradingBinanceDiagnosticsService.class);
        BinanceClient binanceClient = mock(BinanceClient.class);

        Recommendation recommendation = recommendation();
        LiveTradeExecution existing = new LiveTradeExecution();
        existing.setId(UUID.randomUUID());
        existing.setRecommendation(recommendation);
        existing.setTriggerMode(LiveTradeTriggerMode.MANUAL_BUTTON);
        existing.setSymbol("BTCUSDT");
        existing.setSide("BUY");
        existing.setOperatorId("local-operator");
        existing.setExecutionState(LiveTradeExecutionState.OPEN);
        existing.setClientRequestId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        existing.setCreatedAt(Instant.now().minusSeconds(30));
        existing.setUpdatedAt(Instant.now().minusSeconds(5));

        List<LiveTradeExecutionEvent> events = new ArrayList<>();
        when(executionRepository.findFirstByRecommendation_IdAndClientRequestId(
                recommendation.getId(),
                existing.getClientRequestId())).thenReturn(Optional.of(existing));
        when(eventRepository.save(any())).thenAnswer(invocation -> {
            LiveTradeExecutionEvent event = invocation.getArgument(0);
            if (event.getId() == null) {
                event.setId(UUID.randomUUID());
            }
            events.add(event);
            return event;
        });
        when(eventRepository.findByExecution_IdOrderByCreatedAtAsc(existing.getId())).thenReturn(events);

        LiveTradingExecutionService service = new LiveTradingExecutionService(
                recommendationRepository,
                executionRepository,
                eventRepository,
                preflightService,
                reconciliationService,
                new LiveTradingMapper(objectMapper),
                diagnosticsService,
                binanceClient,
                objectMapper);

        LiveTradeExecutionRequestDTO request = new LiveTradeExecutionRequestDTO();
        request.setClientRequestId(existing.getClientRequestId());

        LiveTradeExecutionDTO result = service.executeLive(recommendation.getId(), request, "operator-1", "trace-2", null);

        assertEquals(existing.getId(), result.getId());
        assertEquals("OPEN", result.getExecutionState());
        verifyNoInteractions(recommendationRepository, preflightService, binanceClient, reconciliationService);
    }

    @Test
    void duplicateClientRequestIdRaceReturnsExistingAttemptAfterUniqueConstraintCollision() {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        LiveTradeExecutionEventRepository eventRepository = mock(LiveTradeExecutionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        LiveTradingReconciliationService reconciliationService = mock(LiveTradingReconciliationService.class);
        LiveTradingBinanceDiagnosticsService diagnosticsService = mock(LiveTradingBinanceDiagnosticsService.class);
        BinanceClient binanceClient = mock(BinanceClient.class);

        Recommendation recommendation = recommendation();
        UUID clientRequestId = UUID.fromString("00000000-0000-0000-0000-000000000003");

        LiveTradeExecution existing = new LiveTradeExecution();
        existing.setId(UUID.randomUUID());
        existing.setRecommendation(recommendation);
        existing.setTriggerMode(LiveTradeTriggerMode.MANUAL_BUTTON);
        existing.setSymbol("BTCUSDT");
        existing.setSide("BUY");
        existing.setExecutionState(LiveTradeExecutionState.REQUESTED);
        existing.setClientRequestId(clientRequestId);
        existing.setCreatedAt(Instant.now().minusSeconds(5));
        existing.setUpdatedAt(Instant.now().minusSeconds(5));

        List<LiveTradeExecutionEvent> events = new ArrayList<>();
        when(executionRepository.findFirstByRecommendation_IdAndClientRequestId(recommendation.getId(), clientRequestId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(recommendationRepository.findById(recommendation.getId())).thenReturn(Optional.of(recommendation));
        when(executionRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("uq_live_trade_execution_request"));
        when(eventRepository.save(any())).thenAnswer(invocation -> {
            LiveTradeExecutionEvent event = invocation.getArgument(0);
            if (event.getId() == null) {
                event.setId(UUID.randomUUID());
            }
            events.add(event);
            return event;
        });
        when(eventRepository.findByExecution_IdOrderByCreatedAtAsc(existing.getId())).thenReturn(events);

        LiveTradingExecutionService service = new LiveTradingExecutionService(
                recommendationRepository,
                executionRepository,
                eventRepository,
                preflightService,
                reconciliationService,
                new LiveTradingMapper(objectMapper),
                diagnosticsService,
                binanceClient,
                objectMapper);

        LiveTradeExecutionRequestDTO request = new LiveTradeExecutionRequestDTO();
        request.setClientRequestId(clientRequestId);

        LiveTradeExecutionDTO result = service.executeLive(recommendation.getId(), request, "operator-1", "trace-3", null);

        assertEquals(existing.getId(), result.getId());
        assertEquals("REQUESTED", result.getExecutionState());
        verifyNoInteractions(preflightService, binanceClient, reconciliationService);
    }

    @Test
    void protectionSuccessTransitionsToProtectionActiveAndReconciles() {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        LiveTradeExecutionEventRepository eventRepository = mock(LiveTradeExecutionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        LiveTradingReconciliationService reconciliationService = mock(LiveTradingReconciliationService.class);
        LiveTradingBinanceDiagnosticsService diagnosticsService = mock(LiveTradingBinanceDiagnosticsService.class);
        BinanceClient binanceClient = mock(BinanceClient.class);

        Recommendation recommendation = recommendation();
        LinkedHashMap<UUID, LiveTradeExecution> executions = new LinkedHashMap<>();
        List<LiveTradeExecutionEvent> events = new ArrayList<>();
        stubPersistence(executionRepository, eventRepository, executions, events);

        UUID clientRequestId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        when(executionRepository.findFirstByRecommendation_IdAndClientRequestId(recommendation.getId(), clientRequestId))
                .thenReturn(Optional.empty());
        when(recommendationRepository.findById(recommendation.getId())).thenReturn(Optional.of(recommendation));
        when(preflightService.evaluate(eq(recommendation.getId()), any(UUID.class), isNull()))
                .thenReturn(executablePreflight());
        when(binanceClient.ensureOneWayPositionMode()).thenReturn(Map.of("status", "UNCHANGED"));
        when(binanceClient.ensureIsolatedMargin("BTCUSDT")).thenReturn(Map.of("status", "UNCHANGED"));
        when(binanceClient.setLeverage("BTCUSDT", 5)).thenReturn(Map.of("leverage", 5));
        when(binanceClient.getSymbolInfo("BTCUSDT")).thenReturn(Optional.of(symbolInfo()));
        when(binanceClient.submitOrder(any())).thenReturn(entryResponse("FILLED", "0.010", "100000.0", 11L));
        when(binanceClient.submitAlgoOrder(any()))
                .thenReturn(algoResponse(21L, "sl-algo", "NEW"))
                .thenReturn(algoResponse(22L, "tp-algo", "NEW"));

        LiveTradeExecutionDTO reconciled = new LiveTradeExecutionDTO();
        reconciled.setId(UUID.randomUUID());
        reconciled.setRecommendationId(recommendation.getId());
        reconciled.setExecutionState("PROTECTION_ACTIVE");
        when(reconciliationService.reconcileExecution(any(), any(), any(), eq(false))).thenReturn(reconciled);

        LiveTradingExecutionService service = new LiveTradingExecutionService(
                recommendationRepository,
                executionRepository,
                eventRepository,
                preflightService,
                reconciliationService,
                new LiveTradingMapper(objectMapper),
                diagnosticsService,
                binanceClient,
                objectMapper);

        LiveTradeExecutionRequestDTO request = new LiveTradeExecutionRequestDTO();
        request.setClientRequestId(clientRequestId);

        LiveTradeExecutionDTO result = service.executeLive(recommendation.getId(), request, null, "trace-ok", null);

        assertEquals("PROTECTION_ACTIVE", result.getExecutionState());
        verify(binanceClient, times(1)).submitOrder(any());
        verify(binanceClient, times(2)).submitAlgoOrder(any());
        verify(reconciliationService, times(1)).reconcileExecution(any(), any(), any(), eq(false));
    }

    @Test
    void stopLossFailureSubmitsEmergencyCloseUsingResolvedPositionQuantity() {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        LiveTradeExecutionEventRepository eventRepository = mock(LiveTradeExecutionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        LiveTradingReconciliationService reconciliationService = mock(LiveTradingReconciliationService.class);
        LiveTradingBinanceDiagnosticsService diagnosticsService = mock(LiveTradingBinanceDiagnosticsService.class);
        BinanceClient binanceClient = mock(BinanceClient.class);

        Recommendation recommendation = recommendation();
        LinkedHashMap<UUID, LiveTradeExecution> executions = new LinkedHashMap<>();
        List<LiveTradeExecutionEvent> events = new ArrayList<>();
        stubPersistence(executionRepository, eventRepository, executions, events);

        UUID clientRequestId = UUID.fromString("00000000-0000-0000-0000-000000000112");
        when(executionRepository.findFirstByRecommendation_IdAndClientRequestId(recommendation.getId(), clientRequestId))
                .thenReturn(Optional.empty());
        when(recommendationRepository.findById(recommendation.getId())).thenReturn(Optional.of(recommendation));
        when(preflightService.evaluate(eq(recommendation.getId()), any(UUID.class), isNull()))
                .thenReturn(executablePreflight());
        when(binanceClient.ensureOneWayPositionMode()).thenReturn(Map.of("status", "UNCHANGED"));
        when(binanceClient.ensureIsolatedMargin("BTCUSDT")).thenReturn(Map.of("status", "UNCHANGED"));
        when(binanceClient.setLeverage("BTCUSDT", 5)).thenReturn(Map.of("leverage", 5));
        when(binanceClient.getSymbolInfo("BTCUSDT")).thenReturn(Optional.of(symbolInfo()));
        when(binanceClient.submitOrder(any()))
                .thenReturn(entryResponse("NEW", "0", "0", 31L))
                .thenReturn(entryResponse("FILLED", "0.008", "100100.0", 41L));
        when(binanceClient.getOrder("BTCUSDT", null, 31L))
                .thenReturn(entryResponse("NEW", "0", "0", 31L));
        when(binanceClient.getPositionRisk("BTCUSDT")).thenReturn(List.of(positionRisk("BTCUSDT", "0.008", "100100.0")));
        when(binanceClient.submitAlgoOrder(any()))
                .thenThrow(new RuntimeException("sl failed"))
                .thenReturn(algoResponse(42L, "tp-algo", "NEW"));
        when(diagnosticsService.classifyExecutionFailure(any())).thenReturn(new LiveTradeBlockedReasonDTO(
                LiveTradingBlockerCodes.BINANCE_REJECTED,
                "Algo rejected.",
                "binance",
                Map.of("binanceCode", -4120)));

        LiveTradeExecutionDTO reconciled = new LiveTradeExecutionDTO();
        reconciled.setId(UUID.randomUUID());
        reconciled.setRecommendationId(recommendation.getId());
        reconciled.setExecutionState("EMERGENCY_CLOSE_SUBMITTED");
        when(reconciliationService.reconcileExecution(any(), any(), any(), eq(false))).thenReturn(reconciled);

        LiveTradingExecutionService service = new LiveTradingExecutionService(
                recommendationRepository,
                executionRepository,
                eventRepository,
                preflightService,
                reconciliationService,
                new LiveTradingMapper(objectMapper),
                diagnosticsService,
                binanceClient,
                objectMapper);

        LiveTradeExecutionRequestDTO request = new LiveTradeExecutionRequestDTO();
        request.setClientRequestId(clientRequestId);

        LiveTradeExecutionDTO result = service.executeLive(recommendation.getId(), request, null, "trace-close", null);

        assertEquals("EMERGENCY_CLOSE_SUBMITTED", result.getExecutionState());
        ArgumentCaptor<Map<String, String>> submitCaptor = ArgumentCaptor.forClass(Map.class);
        verify(binanceClient, times(2)).submitOrder(submitCaptor.capture());
        assertEquals("0.008", submitCaptor.getAllValues().get(1).get("quantity"));
        verify(reconciliationService, times(1)).reconcileExecution(any(), any(), any(), eq(false));
    }

    @Test
    void takeProfitFailureKeepsProtectionFailedWithoutEmergencyCloseWhenStopLossIsActive() {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        LiveTradeExecutionEventRepository eventRepository = mock(LiveTradeExecutionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        LiveTradingReconciliationService reconciliationService = mock(LiveTradingReconciliationService.class);
        LiveTradingBinanceDiagnosticsService diagnosticsService = mock(LiveTradingBinanceDiagnosticsService.class);
        BinanceClient binanceClient = mock(BinanceClient.class);

        Recommendation recommendation = recommendation();
        LinkedHashMap<UUID, LiveTradeExecution> executions = new LinkedHashMap<>();
        List<LiveTradeExecutionEvent> events = new ArrayList<>();
        stubPersistence(executionRepository, eventRepository, executions, events);

        UUID clientRequestId = UUID.fromString("00000000-0000-0000-0000-000000000113");
        when(executionRepository.findFirstByRecommendation_IdAndClientRequestId(recommendation.getId(), clientRequestId))
                .thenReturn(Optional.empty());
        when(recommendationRepository.findById(recommendation.getId())).thenReturn(Optional.of(recommendation));
        when(preflightService.evaluate(eq(recommendation.getId()), any(UUID.class), isNull()))
                .thenReturn(executablePreflight());
        when(binanceClient.ensureOneWayPositionMode()).thenReturn(Map.of("status", "UNCHANGED"));
        when(binanceClient.ensureIsolatedMargin("BTCUSDT")).thenReturn(Map.of("status", "UNCHANGED"));
        when(binanceClient.setLeverage("BTCUSDT", 5)).thenReturn(Map.of("leverage", 5));
        when(binanceClient.getSymbolInfo("BTCUSDT")).thenReturn(Optional.of(symbolInfo()));
        when(binanceClient.submitOrder(any())).thenReturn(entryResponse("FILLED", "0.010", "100000.0", 51L));
        when(binanceClient.submitAlgoOrder(any()))
                .thenReturn(algoResponse(61L, "sl-algo", "NEW"))
                .thenThrow(new RuntimeException("tp failed"));
        when(diagnosticsService.classifyExecutionFailure(any())).thenReturn(new LiveTradeBlockedReasonDTO(
                LiveTradingBlockerCodes.BINANCE_REJECTED,
                "TP rejected.",
                "binance",
                Map.of("binanceCode", -4120)));

        LiveTradeExecutionDTO reconciled = new LiveTradeExecutionDTO();
        reconciled.setId(UUID.randomUUID());
        reconciled.setRecommendationId(recommendation.getId());
        reconciled.setExecutionState("PROTECTION_FAILED");
        when(reconciliationService.reconcileExecution(any(), any(), any(), eq(false))).thenReturn(reconciled);

        LiveTradingExecutionService service = new LiveTradingExecutionService(
                recommendationRepository,
                executionRepository,
                eventRepository,
                preflightService,
                reconciliationService,
                new LiveTradingMapper(objectMapper),
                diagnosticsService,
                binanceClient,
                objectMapper);

        LiveTradeExecutionRequestDTO request = new LiveTradeExecutionRequestDTO();
        request.setClientRequestId(clientRequestId);

        LiveTradeExecutionDTO result = service.executeLive(recommendation.getId(), request, null, "trace-tp", null);

        assertEquals("PROTECTION_FAILED", result.getExecutionState());
        verify(binanceClient, times(1)).submitOrder(any());
        verify(binanceClient, times(2)).submitAlgoOrder(any());
        verify(reconciliationService, times(1)).reconcileExecution(any(), any(), any(), eq(false));
    }

    private void stubPersistence(LiveTradeExecutionRepository executionRepository,
            LiveTradeExecutionEventRepository eventRepository,
            LinkedHashMap<UUID, LiveTradeExecution> executions,
            List<LiveTradeExecutionEvent> events) {
        when(executionRepository.save(any())).thenAnswer(invocation -> {
            LiveTradeExecution execution = invocation.getArgument(0);
            if (execution.getId() == null) {
                execution.setId(UUID.randomUUID());
            }
            executions.put(execution.getId(), execution);
            return execution;
        });
        when(executionRepository.findById(any())).thenAnswer(invocation ->
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
                    .filter(event -> event.getExecution() != null && executionId.equals(event.getExecution().getId()))
                    .toList();
        });
    }

    private LiveTradingPreflightDTO executablePreflight() {
        LiveTradingPreflightDTO dto = new LiveTradingPreflightDTO();
        dto.setAllowed(true);
        dto.setExecutable(true);
        return dto;
    }

    private BinanceFuturesOrderResponse entryResponse(String status, String executedQty, String avgPrice, Long orderId) {
        BinanceFuturesOrderResponse response = new BinanceFuturesOrderResponse();
        response.setOrderId(orderId);
        response.setStatus(status);
        response.setExecutedQty(executedQty);
        response.setAvgPrice(avgPrice);
        response.setClientOrderId("client-" + orderId);
        return response;
    }

    private BinanceFuturesAlgoOrderResponse algoResponse(Long algoId, String clientAlgoId, String algoStatus) {
        BinanceFuturesAlgoOrderResponse response = new BinanceFuturesAlgoOrderResponse();
        response.setAlgoId(algoId);
        response.setClientAlgoId(clientAlgoId);
        response.setAlgoStatus(algoStatus);
        response.setOrderType("STOP_MARKET");
        response.setTriggerPrice("98000.0");
        response.setClosePosition(true);
        return response;
    }

    private BinanceExchangeInfoResponse.SymbolInfo symbolInfo() {
        BinanceExchangeInfoResponse.SymbolInfo symbolInfo = new BinanceExchangeInfoResponse.SymbolInfo();
        BinanceExchangeInfoResponse.Filter price = new BinanceExchangeInfoResponse.Filter();
        price.setFilterType("PRICE_FILTER");
        price.setTickSize("0.1");
        BinanceExchangeInfoResponse.Filter lot = new BinanceExchangeInfoResponse.Filter();
        lot.setFilterType("LOT_SIZE");
        lot.setStepSize("0.001");
        lot.setMinQty("0.001");
        symbolInfo.setFilters(List.of(price, lot));
        return symbolInfo;
    }

    private BinanceFuturesPositionRiskResponse positionRisk(String symbol, String qty, String entryPrice) {
        BinanceFuturesPositionRiskResponse response = new BinanceFuturesPositionRiskResponse();
        response.setSymbol(symbol);
        response.setPositionSide("BOTH");
        response.setPositionAmt(qty);
        response.setEntryPrice(entryPrice);
        return response;
    }

    private Recommendation recommendation() {
        Recommendation recommendation = new Recommendation();
        recommendation.setId(UUID.randomUUID());
        recommendation.setScanRun(new ScanRun());
        recommendation.getScanRun().setId(UUID.randomUUID());
        recommendation.setSymbol("BTCUSDT");
        recommendation.setSide("BUY");
        recommendation.setCreatedAt(Instant.now());
        recommendation.setStatus("NEW");

        OrderFields orderFields = new OrderFields();
        orderFields.setRecommendationId(recommendation.getId());
        orderFields.setRecommendation(recommendation);
        orderFields.setEntryOrderJson("""
                {"symbol":"BTCUSDT","side":"BUY","type":"MARKET","quantity":0.010}
                """);
        orderFields.setSlOrderJson("""
                {"symbol":"BTCUSDT","side":"SELL","type":"STOP_MARKET","stopPrice":98000.0,"closePosition":true}
                """);
        orderFields.setTpOrderJson("""
                {"symbol":"BTCUSDT","side":"SELL","type":"TAKE_PROFIT_MARKET","stopPrice":104000.0,"closePosition":true}
                """);
        orderFields.setLeverageRecommendation(5);
        orderFields.setMarginMode("ISOLATED");
        orderFields.setPositionMode("ONE_WAY");
        recommendation.setOrderFields(orderFields);
        return recommendation;
    }
}
