package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
