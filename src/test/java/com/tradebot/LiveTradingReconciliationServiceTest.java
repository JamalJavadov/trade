package com.tradebot;

import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.service.LiveTradingReconciliationService;
import com.tradebot.service.OrderStateSyncService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveTradingReconciliationServiceTest {

    @Test
    void reconcileExecutionDelegatesToOrderStateSyncService() {
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        OrderStateSyncService orderStateSyncService = mock(OrderStateSyncService.class);
        LiveTradingReconciliationService service = new LiveTradingReconciliationService(
                executionRepository,
                orderStateSyncService);

        UUID executionId = UUID.randomUUID();
        LiveTradeExecutionDTO dto = new LiveTradeExecutionDTO();
        dto.setExecutionState("ACTIVE");
        when(orderStateSyncService.reconcileExecution(executionId, "tester", "trace-1", false)).thenReturn(dto);

        LiveTradeExecutionDTO result = service.reconcileExecution(executionId, "tester", "trace-1", false);

        assertEquals("ACTIVE", result.getExecutionState());
        verify(orderStateSyncService).reconcileExecution(executionId, "tester", "trace-1", false);
    }

    @Test
    void scheduledReconcileUsesNewLifecycleStates() {
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        OrderStateSyncService orderStateSyncService = mock(OrderStateSyncService.class);
        LiveTradingReconciliationService service = new LiveTradingReconciliationService(
                executionRepository,
                orderStateSyncService);

        LiveTradeExecution execution = new LiveTradeExecution();
        execution.setId(UUID.randomUUID());
        execution.setTraceId("trace-scheduled");
        execution.setExecutionState(LiveTradeExecutionState.RECONCILING);
        execution.setUpdatedAt(Instant.now().minusSeconds(60));

        when(executionRepository.findByExecutionStateInAndUpdatedAtBeforeOrderByUpdatedAtAsc(any(), any()))
                .thenReturn(List.of(execution));

        service.scheduledReconcile();

        ArgumentCaptor<Collection<LiveTradeExecutionState>> statesCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(executionRepository).findByExecutionStateInAndUpdatedAtBeforeOrderByUpdatedAtAsc(statesCaptor.capture(), any());
        assertTrue(statesCaptor.getValue().contains(LiveTradeExecutionState.ACTIVE));
        assertTrue(statesCaptor.getValue().contains(LiveTradeExecutionState.CLOSING));
        assertTrue(statesCaptor.getValue().contains(LiveTradeExecutionState.RECONCILING));
        verify(orderStateSyncService).reconcileExecution(execution.getId(), "system-scheduler", "trace-scheduled", true);
    }
}
