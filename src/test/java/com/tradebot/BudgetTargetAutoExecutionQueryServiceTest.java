package com.tradebot;

import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.entity.BudgetTargetAuditEventCategory;
import com.tradebot.entity.BudgetTargetAuditSeverity;
import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.BudgetTargetSessionCompletionReason;
import com.tradebot.entity.BudgetTargetSessionEvent;
import com.tradebot.entity.BudgetTargetSessionStatus;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.repository.BudgetTargetSessionEventRepository;
import com.tradebot.repository.BudgetTargetSessionRepository;
import com.tradebot.repository.LiveTradeExecutionEventRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.service.BudgetTargetAutoExecutionLifecycleService;
import com.tradebot.service.BudgetTargetAutoExecutionQueryService;
import com.tradebot.service.ExchangeSyncSnapshotService;
import com.tradebot.service.LiveTradingMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BudgetTargetAutoExecutionQueryServiceTest {

    @Test
    void toSessionDtoExposesStructuredStopAndFailureFields() {
        ControlCenterSettingsProvider settingsProvider = mock(ControlCenterSettingsProvider.class);
        BudgetTargetSessionRepository sessionRepository = mock(BudgetTargetSessionRepository.class);
        BudgetTargetSessionEventRepository eventRepository = mock(BudgetTargetSessionEventRepository.class);
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        LiveTradeExecutionEventRepository executionEventRepository = mock(LiveTradeExecutionEventRepository.class);
        LiveTradingMapper liveTradingMapper = mock(LiveTradingMapper.class);
        BudgetTargetAutoExecutionLifecycleService lifecycleService = mock(BudgetTargetAutoExecutionLifecycleService.class);
        ExchangeSyncSnapshotService exchangeSyncSnapshotService = mock(ExchangeSyncSnapshotService.class);

        BudgetTargetAutoExecutionQueryService service = new BudgetTargetAutoExecutionQueryService(
                settingsProvider,
                sessionRepository,
                eventRepository,
                executionRepository,
                executionEventRepository,
                liveTradingMapper,
                lifecycleService,
                exchangeSyncSnapshotService);

        BudgetTargetSession session = new BudgetTargetSession();
        session.setId(UUID.randomUUID());
        session.setStatus(BudgetTargetSessionStatus.STOPPING);
        session.setCompletionReason(BudgetTargetSessionCompletionReason.READ_ONLY_ENABLED);
        session.setStopReason("READ_ONLY_ENABLED");
        session.setBudgetAmountUsdt(new BigDecimal("100"));
        session.setTargetProfitUsdt(new BigDecimal("25"));
        session.setRealizedNetPnlUsdt(new BigDecimal("12.50"));
        session.setUnrealizedNetPnlUsdt(new BigDecimal("4.25"));
        session.setMaxConcurrentPositions(3);
        session.setActivePositionsCount(1);
        session.setOpenedPositionsTotal(4);
        session.setClosedPositionsTotal(3);
        session.setStopRequested(true);
        session.setLastErrorCode("UPSTREAM_TIMEOUT");
        session.setLastErrorMessage("Close-all submission timed out.");
        session.setExecutionFailureCount(2);
        session.setStartedAt(Instant.parse("2026-03-09T00:00:00Z"));
        session.setUpdatedAt(Instant.parse("2026-03-09T00:10:00Z"));
        session.setCreatedAt(Instant.parse("2026-03-09T00:00:00Z"));
        session.setConfigSnapshotJson("{}");

        BudgetTargetSessionEvent stopEvent = new BudgetTargetSessionEvent();
        stopEvent.setId(UUID.randomUUID());
        stopEvent.setSession(session);
        stopEvent.setEventType("STOP_REQUESTED");
        stopEvent.setEventStatus("STOPPING");
        stopEvent.setReasonCode("READ_ONLY_ENABLED");
        stopEvent.setEventCategory(BudgetTargetAuditEventCategory.SESSION);
        stopEvent.setSeverity(BudgetTargetAuditSeverity.WARN);
        stopEvent.setNotes("Budget-target auto-execution entered READ-ONLY mode while the session was active.");
        stopEvent.setEventTs(Instant.parse("2026-03-09T00:09:00Z"));

        when(executionRepository.countBySession_IdAndExecutionStatusIn(eq(session.getId()), any())).thenReturn(1L);
        when(executionRepository.countBySession_Id(session.getId())).thenReturn(4L);
        when(executionRepository.findBySession_IdAndExecutionStatusInOrderByCreatedAtAsc(eq(session.getId()), any()))
                .thenReturn(List.of(activeExecution(new BigDecimal("30"))));
        when(eventRepository.findTop50BySession_IdOrderByEventTsDesc(session.getId())).thenReturn(List.of(stopEvent));
        com.tradebot.dto.BudgetTargetSyncHealthDTO syncHealth = new com.tradebot.dto.BudgetTargetSyncHealthDTO();
        syncHealth.setStatus("IDLE");
        when(exchangeSyncSnapshotService.summarizeSession(session.getId())).thenReturn(syncHealth);

        var dto = service.toSessionDto(session);

        assertEquals("READ_ONLY_ENABLED", dto.getStopReason());
        assertEquals(
                "Budget-target auto-execution entered READ-ONLY mode while the session was active.",
                dto.getStopReasonMessage());
        assertEquals("UPSTREAM_TIMEOUT", dto.getFailureReasonCode());
        assertEquals("Close-all submission timed out.", dto.getFailureReasonMessage());
        assertEquals(new BigDecimal("82.50"), dto.getRemainingBankrollUsdt());
        assertEquals("IDLE", dto.getSyncHealth().getStatus());
    }

    private LiveTradeExecution activeExecution(BigDecimal reservedMarginUsdt) {
        LiveTradeExecution execution = new LiveTradeExecution();
        execution.setId(UUID.randomUUID());
        execution.setReservedMarginUsdt(reservedMarginUsdt);
        execution.setCreatedAt(Instant.now());
        execution.setUpdatedAt(Instant.now());
        return execution;
    }
}
