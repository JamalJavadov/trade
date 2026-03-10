package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.dto.LiveTradeExecutionRequestDTO;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeTriggerMode;
import com.tradebot.entity.Recommendation;
import com.tradebot.repository.LiveTradeExecutionEventRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.security.LocalMutationGuard;
import com.tradebot.service.ApprovedExecutionCommand;
import com.tradebot.service.LiveExecutionEngineService;
import com.tradebot.service.LiveTradingExecutionService;
import com.tradebot.service.LiveTradingMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveTradingExecutionServiceTest {

    @Test
    void executeLiveDelegatesManualCommandToEngine() {
        LiveExecutionEngineService engineService = mock(LiveExecutionEngineService.class);
        LiveTradingExecutionService service = new LiveTradingExecutionService(
                mock(LiveTradeExecutionRepository.class),
                mock(LiveTradeExecutionEventRepository.class),
                new LiveTradingMapper(new ObjectMapper()),
                engineService);

        UUID recommendationId = UUID.randomUUID();
        UUID clientRequestId = UUID.randomUUID();
        LocalMutationGuard.LocalRequestCheck localRequestCheck = new LocalMutationGuard.LocalRequestCheck(
                true,
                "127.0.0.1",
                null,
                "http://localhost:5173",
                null);
        LiveTradeExecutionDTO expected = new LiveTradeExecutionDTO();
        expected.setExecutionState("CREATED");
        when(engineService.execute(any(), eq(localRequestCheck))).thenReturn(expected);

        LiveTradeExecutionRequestDTO request = new LiveTradeExecutionRequestDTO();
        request.setClientRequestId(clientRequestId);
        request.setOperatorNote("manual note");

        LiveTradeExecutionDTO result = service.executeLive(
                recommendationId,
                request,
                "operator-1",
                "trace-1",
                localRequestCheck);

        ArgumentCaptor<ApprovedExecutionCommand> commandCaptor = ArgumentCaptor.forClass(ApprovedExecutionCommand.class);
        verify(engineService).execute(commandCaptor.capture(), eq(localRequestCheck));
        ApprovedExecutionCommand command = commandCaptor.getValue();
        assertEquals(recommendationId, command.recommendationId());
        assertNull(command.sessionId());
        assertEquals(clientRequestId, command.idempotencyKey());
        assertEquals(LiveTradeTriggerMode.MANUAL_BUTTON, command.triggerMode());
        assertEquals("operator-1", command.operatorId());
        assertEquals("trace-1", command.traceId());
        assertEquals("manual note", command.operatorNote());
        assertEquals(expected, result);
    }

    @Test
    void executeAutoSessionUsesDeterministicIdempotencyKey() {
        LiveExecutionEngineService engineService = mock(LiveExecutionEngineService.class);
        LiveTradingExecutionService service = new LiveTradingExecutionService(
                mock(LiveTradeExecutionRepository.class),
                mock(LiveTradeExecutionEventRepository.class),
                new LiveTradingMapper(new ObjectMapper()),
                engineService);

        UUID recommendationId = UUID.fromString("00000000-0000-0000-0000-000000000123");
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000456");
        when(engineService.execute(any(), eq(null))).thenReturn(new LiveTradeExecutionDTO());

        service.executeAutoSession(
                recommendationId,
                sessionId,
                new BigDecimal("12.50"),
                "system",
                "trace-auto");

        ArgumentCaptor<ApprovedExecutionCommand> commandCaptor = ArgumentCaptor.forClass(ApprovedExecutionCommand.class);
        verify(engineService).execute(commandCaptor.capture(), eq(null));
        ApprovedExecutionCommand command = commandCaptor.getValue();
        assertEquals(sessionId, command.sessionId());
        assertEquals(new BigDecimal("12.50"), command.allocatedBudgetSliceUsdt());
        assertEquals(
                ApprovedExecutionCommand.deterministicKey("AUTO_SESSION|" + sessionId + "|" + recommendationId),
                command.idempotencyKey());
        assertEquals(LiveTradeTriggerMode.AUTO_SESSION, command.triggerMode());
    }

    @Test
    void listExecutionsMapsRepositoryRowsWithoutCallingEngine() {
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        LiveTradeExecutionEventRepository eventRepository = mock(LiveTradeExecutionEventRepository.class);
        LiveExecutionEngineService engineService = mock(LiveExecutionEngineService.class);
        LiveTradingExecutionService service = new LiveTradingExecutionService(
                executionRepository,
                eventRepository,
                new LiveTradingMapper(new ObjectMapper()),
                engineService);

        Recommendation recommendation = new Recommendation();
        recommendation.setId(UUID.randomUUID());

        LiveTradeExecution execution = new LiveTradeExecution();
        execution.setId(UUID.randomUUID());
        execution.setRecommendation(recommendation);
        execution.setTriggerMode(LiveTradeTriggerMode.MANUAL_BUTTON);
        execution.setSymbol("BTCUSDT");
        execution.setSide("BUY");
        execution.setExecutionState(com.tradebot.entity.LiveTradeExecutionState.ACTIVE);
        execution.setCreatedAt(Instant.now());
        execution.setUpdatedAt(Instant.now());

        when(executionRepository.findTop20ByRecommendation_IdOrderByCreatedAtDesc(recommendation.getId()))
                .thenReturn(List.of(execution));
        when(eventRepository.findByExecution_IdOrderByCreatedAtAsc(execution.getId())).thenReturn(List.of());

        LiveTradeExecutionDTO dto = service.listExecutions(recommendation.getId(), 10).getFirst();

        assertEquals("ACTIVE", dto.getExecutionState());
        verify(engineService, never()).execute(any(), any());
    }
}
