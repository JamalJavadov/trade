package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.dto.BudgetTargetAutoExecutionStateRequestDTO;
import com.tradebot.entity.BudgetTargetAuditEventCategory;
import com.tradebot.entity.BudgetTargetAuditSeverity;
import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.BudgetTargetSessionCompletionReason;
import com.tradebot.entity.BudgetTargetSessionEvent;
import com.tradebot.entity.BudgetTargetSessionStatus;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.repository.BudgetTargetSessionEventRepository;
import com.tradebot.repository.BudgetTargetSessionRepository;
import com.tradebot.service.BudgetTargetAutoExecutionLifecycleService;
import com.tradebot.service.BudgetTargetSessionStreamPublisher;
import com.tradebot.service.LiveTradingPreflightService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BudgetTargetAutoExecutionLifecycleServiceTest {

    @Test
    void turnOnCreatesDraftThenArmedAndPromotesToRunningWhenChecksPass() {
        ControlCenterSettingsProvider settingsProvider = mock(ControlCenterSettingsProvider.class);
        BudgetTargetSessionRepository sessionRepository = mock(BudgetTargetSessionRepository.class);
        BudgetTargetSessionEventRepository eventRepository = mock(BudgetTargetSessionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        BudgetTargetSessionStreamPublisher streamPublisher = mock(BudgetTargetSessionStreamPublisher.class);

        ControlCenterConfig.BudgetTargetAutoExecution runtime = new ControlCenterConfig.BudgetTargetAutoExecution();
        runtime.setEnabled(true);
        runtime.setArmed(false);
        runtime.setRequireBinanceHealthPass(false);

        ControlCenterConfig snapshot = new ControlCenterConfig();
        snapshot.getBudgetTargetAutoExecution().setEnabled(true);
        snapshot.getBudgetTargetAutoExecution().setArmed(true);
        snapshot.getBudgetTargetAutoExecution().setRequireBinanceHealthPass(false);
        snapshot.getBudgetTargetAutoExecution().setDefaultBudgetUsdt(new BigDecimal("75"));
        snapshot.getBudgetTargetAutoExecution().setDefaultTargetProfitUsdt(new BigDecimal("12"));
        snapshot.getBudgetTargetAutoExecution().setMaxConcurrentPositions(4);

        when(settingsProvider.getBudgetTargetAutoExecutionSettings()).thenReturn(runtime);
        when(settingsProvider.getConfigSnapshot()).thenReturn(snapshot);
        when(settingsProvider.getCurrentVersion()).thenReturn(11);
        when(settingsProvider.getUpdatedAt()).thenReturn(Instant.parse("2026-03-09T00:00:00Z"));
        when(settingsProvider.can("live.execution.enabled")).thenReturn(true);
        when(settingsProvider.patchOperational(any(), any(), any())).thenReturn(snapshot);
        when(sessionRepository.findFirstByStatusInOrderByCreatedAtDesc(any())).thenReturn(Optional.empty());
        List<BudgetTargetSession> savedSnapshots = new ArrayList<>();
        List<BudgetTargetSessionEvent> savedEvents = new ArrayList<>();
        when(sessionRepository.save(any(BudgetTargetSession.class))).thenAnswer(invocation -> {
            BudgetTargetSession session = invocation.getArgument(0);
            savedSnapshots.add(copySession(session));
            return session;
        });
        when(eventRepository.save(any(BudgetTargetSessionEvent.class))).thenAnswer(invocation -> {
            BudgetTargetSessionEvent event = invocation.getArgument(0);
            savedEvents.add(event);
            return event;
        });

        BudgetTargetAutoExecutionLifecycleService service = new BudgetTargetAutoExecutionLifecycleService(
                settingsProvider,
                sessionRepository,
                eventRepository,
                preflightService,
                streamPublisher,
                new ObjectMapper().findAndRegisterModules());

        BudgetTargetAutoExecutionStateRequestDTO request = new BudgetTargetAutoExecutionStateRequestDTO();
        request.setCommand(BudgetTargetAutoExecutionStateRequestDTO.Command.TURN_ON);
        request.setBudgetAmountUsdt(new BigDecimal("110"));
        request.setTargetProfitUsdt(new BigDecimal("21"));

        service.applyCommand(request, "tester");

        verify(sessionRepository, atLeastOnce()).save(any(BudgetTargetSession.class));
        assertEquals(BudgetTargetSessionStatus.DRAFT, savedSnapshots.get(0).getStatus());
        assertEquals(BudgetTargetSessionStatus.ARMED, savedSnapshots.get(1).getStatus());
        assertEquals(BudgetTargetSessionStatus.RUNNING, savedSnapshots.get(savedSnapshots.size() - 1).getStatus());
        assertEquals(new BigDecimal("110"), savedSnapshots.get(0).getBudgetAmountUsdt());
        assertEquals(new BigDecimal("21"), savedSnapshots.get(0).getTargetProfitUsdt());
        assertEquals(3, savedSnapshots.get(0).getMaxConcurrentPositions());
        try {
            com.fasterxml.jackson.databind.JsonNode snapshotJson = new ObjectMapper()
                    .readTree(savedSnapshots.get(0).getConfigSnapshotJson());
            assertEquals(true, snapshotJson.path("autoTargetMode").path("armed").asBoolean());
        } catch (Exception ex) {
            throw new AssertionError("Failed to parse configSnapshotJson", ex);
        }
        BudgetTargetSessionEvent draftedEvent = savedEvents.getFirst();
        assertEquals("SESSION_DRAFTED", draftedEvent.getEventType());
        assertEquals(BudgetTargetAuditEventCategory.SESSION, draftedEvent.getEventCategory());
        assertEquals(BudgetTargetAuditSeverity.INFO, draftedEvent.getSeverity());
        assertEquals("tester", draftedEvent.getActor());
        assertNotNull(draftedEvent.getBeforeJson());
        assertNotNull(draftedEvent.getAfterJson());
        org.assertj.core.api.Assertions.assertThat(draftedEvent.getAfterJson()).contains("startReason");
        verify(settingsProvider).patchOperational(any(), eq("budget-target-auto-session-turn-on"), eq("tester"));
    }

    @Test
    void turnOnRejectsNonPositiveRequestedBudget() {
        ControlCenterSettingsProvider settingsProvider = mock(ControlCenterSettingsProvider.class);
        BudgetTargetSessionRepository sessionRepository = mock(BudgetTargetSessionRepository.class);
        BudgetTargetSessionEventRepository eventRepository = mock(BudgetTargetSessionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        BudgetTargetSessionStreamPublisher streamPublisher = mock(BudgetTargetSessionStreamPublisher.class);

        ControlCenterConfig snapshot = new ControlCenterConfig();
        snapshot.getBudgetTargetAutoExecution().setEnabled(true);
        snapshot.getBudgetTargetAutoExecution().setAllowNewSessionStart(true);
        snapshot.getBudgetTargetAutoExecution().setKillSwitch(false);

        when(settingsProvider.getConfigSnapshot()).thenReturn(snapshot);
        when(sessionRepository.findFirstByStatusInOrderByCreatedAtDesc(any())).thenReturn(Optional.empty());

        BudgetTargetAutoExecutionLifecycleService service = new BudgetTargetAutoExecutionLifecycleService(
                settingsProvider,
                sessionRepository,
                eventRepository,
                preflightService,
                streamPublisher,
                new ObjectMapper().findAndRegisterModules());

        BudgetTargetAutoExecutionStateRequestDTO request = new BudgetTargetAutoExecutionStateRequestDTO();
        request.setCommand(BudgetTargetAutoExecutionStateRequestDTO.Command.TURN_ON);
        request.setBudgetAmountUsdt(BigDecimal.ZERO);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.applyCommand(request, "tester"));

        assertEquals("budgetAmountUsdt must be greater than 0", error.getMessage());
    }

    @Test
    void turnOnRejectsNonPositiveRequestedTarget() {
        ControlCenterSettingsProvider settingsProvider = mock(ControlCenterSettingsProvider.class);
        BudgetTargetSessionRepository sessionRepository = mock(BudgetTargetSessionRepository.class);
        BudgetTargetSessionEventRepository eventRepository = mock(BudgetTargetSessionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        BudgetTargetSessionStreamPublisher streamPublisher = mock(BudgetTargetSessionStreamPublisher.class);

        ControlCenterConfig snapshot = new ControlCenterConfig();
        snapshot.getBudgetTargetAutoExecution().setEnabled(true);
        snapshot.getBudgetTargetAutoExecution().setAllowNewSessionStart(true);
        snapshot.getBudgetTargetAutoExecution().setKillSwitch(false);

        when(settingsProvider.getConfigSnapshot()).thenReturn(snapshot);
        when(sessionRepository.findFirstByStatusInOrderByCreatedAtDesc(any())).thenReturn(Optional.empty());

        BudgetTargetAutoExecutionLifecycleService service = new BudgetTargetAutoExecutionLifecycleService(
                settingsProvider,
                sessionRepository,
                eventRepository,
                preflightService,
                streamPublisher,
                new ObjectMapper().findAndRegisterModules());

        BudgetTargetAutoExecutionStateRequestDTO request = new BudgetTargetAutoExecutionStateRequestDTO();
        request.setCommand(BudgetTargetAutoExecutionStateRequestDTO.Command.TURN_ON);
        request.setTargetProfitUsdt(BigDecimal.ZERO);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.applyCommand(request, "tester"));

        assertEquals("targetProfitUsdt must be greater than 0", error.getMessage());
    }

    @Test
    void turnOnRejectsWhenFeatureIsDisabled() {
        ControlCenterSettingsProvider settingsProvider = mock(ControlCenterSettingsProvider.class);
        BudgetTargetSessionRepository sessionRepository = mock(BudgetTargetSessionRepository.class);
        BudgetTargetSessionEventRepository eventRepository = mock(BudgetTargetSessionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        BudgetTargetSessionStreamPublisher streamPublisher = mock(BudgetTargetSessionStreamPublisher.class);

        ControlCenterConfig snapshot = new ControlCenterConfig();
        snapshot.getBudgetTargetAutoExecution().setEnabled(false);
        when(settingsProvider.getConfigSnapshot()).thenReturn(snapshot);

        BudgetTargetAutoExecutionLifecycleService service = new BudgetTargetAutoExecutionLifecycleService(
                settingsProvider,
                sessionRepository,
                eventRepository,
                preflightService,
                streamPublisher,
                new ObjectMapper().findAndRegisterModules());

        BudgetTargetAutoExecutionStateRequestDTO request = new BudgetTargetAutoExecutionStateRequestDTO();
        request.setCommand(BudgetTargetAutoExecutionStateRequestDTO.Command.TURN_ON);
        request.setBudgetAmountUsdt(new BigDecimal("50"));
        request.setTargetProfitUsdt(new BigDecimal("10"));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.applyCommand(request, "tester"));
        assertEquals("Budget-target auto-execution is disabled in Control Center.", error.getMessage());
    }

    @Test
    void turnOnRejectsWhenNewSessionStartsAreDisabled() {
        ControlCenterSettingsProvider settingsProvider = mock(ControlCenterSettingsProvider.class);
        BudgetTargetSessionRepository sessionRepository = mock(BudgetTargetSessionRepository.class);
        BudgetTargetSessionEventRepository eventRepository = mock(BudgetTargetSessionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        BudgetTargetSessionStreamPublisher streamPublisher = mock(BudgetTargetSessionStreamPublisher.class);

        ControlCenterConfig snapshot = new ControlCenterConfig();
        snapshot.getBudgetTargetAutoExecution().setEnabled(true);
        snapshot.getBudgetTargetAutoExecution().setAllowNewSessionStart(false);
        when(settingsProvider.getConfigSnapshot()).thenReturn(snapshot);

        BudgetTargetAutoExecutionLifecycleService service = new BudgetTargetAutoExecutionLifecycleService(
                settingsProvider,
                sessionRepository,
                eventRepository,
                preflightService,
                streamPublisher,
                new ObjectMapper().findAndRegisterModules());

        BudgetTargetAutoExecutionStateRequestDTO request = new BudgetTargetAutoExecutionStateRequestDTO();
        request.setCommand(BudgetTargetAutoExecutionStateRequestDTO.Command.TURN_ON);
        request.setBudgetAmountUsdt(new BigDecimal("50"));
        request.setTargetProfitUsdt(new BigDecimal("10"));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.applyCommand(request, "tester"));
        assertEquals("Budget-target auto-execution new session starts are disabled.", error.getMessage());
    }

    @Test
    void turnOnRejectsWhenKillSwitchIsEnabled() {
        ControlCenterSettingsProvider settingsProvider = mock(ControlCenterSettingsProvider.class);
        BudgetTargetSessionRepository sessionRepository = mock(BudgetTargetSessionRepository.class);
        BudgetTargetSessionEventRepository eventRepository = mock(BudgetTargetSessionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        BudgetTargetSessionStreamPublisher streamPublisher = mock(BudgetTargetSessionStreamPublisher.class);

        ControlCenterConfig snapshot = new ControlCenterConfig();
        snapshot.getBudgetTargetAutoExecution().setEnabled(true);
        snapshot.getBudgetTargetAutoExecution().setAllowNewSessionStart(true);
        snapshot.getBudgetTargetAutoExecution().setKillSwitch(true);
        when(settingsProvider.getConfigSnapshot()).thenReturn(snapshot);

        BudgetTargetAutoExecutionLifecycleService service = new BudgetTargetAutoExecutionLifecycleService(
                settingsProvider,
                sessionRepository,
                eventRepository,
                preflightService,
                streamPublisher,
                new ObjectMapper().findAndRegisterModules());

        BudgetTargetAutoExecutionStateRequestDTO request = new BudgetTargetAutoExecutionStateRequestDTO();
        request.setCommand(BudgetTargetAutoExecutionStateRequestDTO.Command.TURN_ON);
        request.setBudgetAmountUsdt(new BigDecimal("50"));
        request.setTargetProfitUsdt(new BigDecimal("10"));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.applyCommand(request, "tester"));
        assertEquals("Budget-target auto-execution kill switch is enabled.", error.getMessage());
    }

    @Test
    void turnOnRejectsWhenAnotherSessionIsAlreadyActive() {
        ControlCenterSettingsProvider settingsProvider = mock(ControlCenterSettingsProvider.class);
        BudgetTargetSessionRepository sessionRepository = mock(BudgetTargetSessionRepository.class);
        BudgetTargetSessionEventRepository eventRepository = mock(BudgetTargetSessionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        BudgetTargetSessionStreamPublisher streamPublisher = mock(BudgetTargetSessionStreamPublisher.class);

        ControlCenterConfig snapshot = new ControlCenterConfig();
        snapshot.getBudgetTargetAutoExecution().setEnabled(true);
        snapshot.getBudgetTargetAutoExecution().setAllowNewSessionStart(true);
        snapshot.getBudgetTargetAutoExecution().setKillSwitch(false);
        when(settingsProvider.getConfigSnapshot()).thenReturn(snapshot);

        BudgetTargetSession activeSession = new BudgetTargetSession();
        activeSession.setStatus(BudgetTargetSessionStatus.RUNNING);
        when(sessionRepository.findFirstByStatusInOrderByCreatedAtDesc(any())).thenReturn(Optional.of(activeSession));

        BudgetTargetAutoExecutionLifecycleService service = new BudgetTargetAutoExecutionLifecycleService(
                settingsProvider,
                sessionRepository,
                eventRepository,
                preflightService,
                streamPublisher,
                new ObjectMapper().findAndRegisterModules());

        BudgetTargetAutoExecutionStateRequestDTO request = new BudgetTargetAutoExecutionStateRequestDTO();
        request.setCommand(BudgetTargetAutoExecutionStateRequestDTO.Command.TURN_ON);
        request.setBudgetAmountUsdt(new BigDecimal("50"));
        request.setTargetProfitUsdt(new BigDecimal("10"));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.applyCommand(request, "tester"));
        assertEquals("A budget-target auto-execution session is already active.", error.getMessage());
    }

    @Test
    void turnOnLeavesSessionArmedWhenLiveExecutionCapabilityIsDisabled() {
        ControlCenterSettingsProvider settingsProvider = mock(ControlCenterSettingsProvider.class);
        BudgetTargetSessionRepository sessionRepository = mock(BudgetTargetSessionRepository.class);
        BudgetTargetSessionEventRepository eventRepository = mock(BudgetTargetSessionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        BudgetTargetSessionStreamPublisher streamPublisher = mock(BudgetTargetSessionStreamPublisher.class);

        ControlCenterConfig.BudgetTargetAutoExecution runtime = new ControlCenterConfig.BudgetTargetAutoExecution();
        runtime.setEnabled(true);
        runtime.setArmed(false);
        runtime.setRequireBinanceHealthPass(false);

        ControlCenterConfig snapshot = new ControlCenterConfig();
        snapshot.getBudgetTargetAutoExecution().setEnabled(true);
        snapshot.getBudgetTargetAutoExecution().setAllowNewSessionStart(true);
        snapshot.getBudgetTargetAutoExecution().setKillSwitch(false);
        snapshot.getBudgetTargetAutoExecution().setRequireBinanceHealthPass(false);
        snapshot.getBudgetTargetAutoExecution().setDefaultBudgetUsdt(new BigDecimal("75"));
        snapshot.getBudgetTargetAutoExecution().setDefaultTargetProfitUsdt(new BigDecimal("12"));

        when(settingsProvider.getBudgetTargetAutoExecutionSettings()).thenReturn(runtime);
        when(settingsProvider.getConfigSnapshot()).thenReturn(snapshot);
        when(settingsProvider.getCurrentVersion()).thenReturn(11);
        when(settingsProvider.getUpdatedAt()).thenReturn(Instant.parse("2026-03-09T00:00:00Z"));
        when(settingsProvider.can("live.execution.enabled")).thenReturn(false);
        when(settingsProvider.patchOperational(any(), any(), any())).thenReturn(snapshot);
        when(sessionRepository.findFirstByStatusInOrderByCreatedAtDesc(any())).thenReturn(Optional.empty());

        List<BudgetTargetSession> savedSnapshots = new ArrayList<>();
        List<BudgetTargetSessionEvent> savedEvents = new ArrayList<>();
        when(sessionRepository.save(any(BudgetTargetSession.class))).thenAnswer(invocation -> {
            BudgetTargetSession session = invocation.getArgument(0);
            savedSnapshots.add(copySession(session));
            return session;
        });
        when(eventRepository.save(any(BudgetTargetSessionEvent.class))).thenAnswer(invocation -> {
            BudgetTargetSessionEvent event = invocation.getArgument(0);
            savedEvents.add(event);
            return event;
        });

        BudgetTargetAutoExecutionLifecycleService service = new BudgetTargetAutoExecutionLifecycleService(
                settingsProvider,
                sessionRepository,
                eventRepository,
                preflightService,
                streamPublisher,
                new ObjectMapper().findAndRegisterModules());

        BudgetTargetAutoExecutionStateRequestDTO request = new BudgetTargetAutoExecutionStateRequestDTO();
        request.setCommand(BudgetTargetAutoExecutionStateRequestDTO.Command.TURN_ON);
        request.setBudgetAmountUsdt(new BigDecimal("110"));
        request.setTargetProfitUsdt(new BigDecimal("21"));

        service.applyCommand(request, "tester");

        assertEquals(BudgetTargetSessionStatus.ARMED, savedSnapshots.get(savedSnapshots.size() - 1).getStatus());
        assertEquals("LIVE_EXECUTION_DISABLED", savedSnapshots.get(savedSnapshots.size() - 1).getLastErrorCode());
        assertEquals(
                "Live execution capability is disabled in Control Center.",
                savedSnapshots.get(savedSnapshots.size() - 1).getLastErrorMessage());
        BudgetTargetSessionEvent blockedEvent = savedEvents.get(savedEvents.size() - 1);
        assertEquals("SESSION_BLOCKED", blockedEvent.getEventType());
        assertEquals("LIVE_EXECUTION_DISABLED", blockedEvent.getReasonCode());
    }

    @Test
    void completeSessionUsesCancelledWhenNothingOpened() {
        ControlCenterSettingsProvider settingsProvider = mock(ControlCenterSettingsProvider.class);
        BudgetTargetSessionRepository sessionRepository = mock(BudgetTargetSessionRepository.class);
        BudgetTargetSessionEventRepository eventRepository = mock(BudgetTargetSessionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        BudgetTargetSessionStreamPublisher streamPublisher = mock(BudgetTargetSessionStreamPublisher.class);
        ControlCenterConfig.BudgetTargetAutoExecution config = new ControlCenterConfig.BudgetTargetAutoExecution();
        config.setEnabled(false);
        when(settingsProvider.getBudgetTargetAutoExecutionSettings()).thenReturn(config);
        when(sessionRepository.save(any(BudgetTargetSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any(BudgetTargetSessionEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(settingsProvider.patchOperational(any(), any(), any())).thenReturn(new ControlCenterConfig());

        BudgetTargetAutoExecutionLifecycleService service = new BudgetTargetAutoExecutionLifecycleService(
                settingsProvider,
                sessionRepository,
                eventRepository,
                preflightService,
                streamPublisher,
                new ObjectMapper().findAndRegisterModules());

        BudgetTargetSession session = new BudgetTargetSession();
        session.setStatus(BudgetTargetSessionStatus.STOPPING);
        session.setOpenedPositionsTotal(0);
        session.setTraceId("trace-cancel");
        session.setBudgetAmountUsdt(new BigDecimal("50"));
        session.setTargetProfitUsdt(new BigDecimal("10"));
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        session.setConfigSnapshotJson("{}");

        BudgetTargetSession updated = service.completeSession(session, BudgetTargetSessionCompletionReason.OPERATOR_STOPPED, "done");

        assertEquals(BudgetTargetSessionStatus.CANCELLED, updated.getStatus());
        assertEquals("CANCELLED", updated.getStopReason());
        assertNotNull(updated.getEndedAt());
    }

    @Test
    void completeSessionUsesTargetReachedWhenProfitGoalTriggered() {
        ControlCenterSettingsProvider settingsProvider = mock(ControlCenterSettingsProvider.class);
        BudgetTargetSessionRepository sessionRepository = mock(BudgetTargetSessionRepository.class);
        BudgetTargetSessionEventRepository eventRepository = mock(BudgetTargetSessionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        BudgetTargetSessionStreamPublisher streamPublisher = mock(BudgetTargetSessionStreamPublisher.class);
        ControlCenterConfig.BudgetTargetAutoExecution config = new ControlCenterConfig.BudgetTargetAutoExecution();
        config.setEnabled(false);
        when(settingsProvider.getBudgetTargetAutoExecutionSettings()).thenReturn(config);
        when(sessionRepository.save(any(BudgetTargetSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any(BudgetTargetSessionEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(settingsProvider.patchOperational(any(), any(), any())).thenReturn(new ControlCenterConfig());

        BudgetTargetAutoExecutionLifecycleService service = new BudgetTargetAutoExecutionLifecycleService(
                settingsProvider,
                sessionRepository,
                eventRepository,
                preflightService,
                streamPublisher,
                new ObjectMapper().findAndRegisterModules());

        BudgetTargetSession session = new BudgetTargetSession();
        session.setStatus(BudgetTargetSessionStatus.STOPPING);
        session.setOpenedPositionsTotal(1);
        session.setTraceId("trace-target");
        session.setBudgetAmountUsdt(new BigDecimal("50"));
        session.setTargetProfitUsdt(new BigDecimal("10"));
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        session.setConfigSnapshotJson("{}");

        BudgetTargetSession updated = service.completeSession(session, BudgetTargetSessionCompletionReason.TARGET_REACHED, "done");

        assertEquals(BudgetTargetSessionStatus.STOPPED, updated.getStatus());
        assertEquals("TARGET_REACHED", updated.getStopReason());
        assertNotNull(updated.getEndedAt());
    }

    @Test
    void turnOffRequiresExplicitConfirmStopWhenConfigured() {
        ControlCenterSettingsProvider settingsProvider = mock(ControlCenterSettingsProvider.class);
        BudgetTargetSessionRepository sessionRepository = mock(BudgetTargetSessionRepository.class);
        BudgetTargetSessionEventRepository eventRepository = mock(BudgetTargetSessionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        BudgetTargetSessionStreamPublisher streamPublisher = mock(BudgetTargetSessionStreamPublisher.class);

        ControlCenterConfig.BudgetTargetAutoExecution config = new ControlCenterConfig.BudgetTargetAutoExecution();
        config.setEnabled(true);
        config.setArmed(true);
        config.setRequireOperatorConfirmationForStop(true);
        when(settingsProvider.getBudgetTargetAutoExecutionSettings()).thenReturn(config);

        BudgetTargetAutoExecutionLifecycleService service = new BudgetTargetAutoExecutionLifecycleService(
                settingsProvider,
                sessionRepository,
                eventRepository,
                preflightService,
                streamPublisher,
                new ObjectMapper().findAndRegisterModules());

        BudgetTargetAutoExecutionStateRequestDTO request = new BudgetTargetAutoExecutionStateRequestDTO();
        request.setCommand(BudgetTargetAutoExecutionStateRequestDTO.Command.TURN_OFF);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.applyCommand(request, "tester"));
        assertEquals("confirmStop=true is required before turning budget-target auto-execution OFF.", error.getMessage());
    }

    @Test
    void turnOffTransitionsActiveSessionToStoppingWithStructuredOperatorStopReason() {
        ControlCenterSettingsProvider settingsProvider = mock(ControlCenterSettingsProvider.class);
        BudgetTargetSessionRepository sessionRepository = mock(BudgetTargetSessionRepository.class);
        BudgetTargetSessionEventRepository eventRepository = mock(BudgetTargetSessionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        BudgetTargetSessionStreamPublisher streamPublisher = mock(BudgetTargetSessionStreamPublisher.class);

        ControlCenterConfig.BudgetTargetAutoExecution config = new ControlCenterConfig.BudgetTargetAutoExecution();
        config.setEnabled(true);
        config.setArmed(true);
        config.setRequireOperatorConfirmationForStop(false);
        when(settingsProvider.getBudgetTargetAutoExecutionSettings()).thenReturn(config);
        when(settingsProvider.patchOperational(any(), any(), any())).thenReturn(new ControlCenterConfig());

        BudgetTargetSession session = new BudgetTargetSession();
        session.setStatus(BudgetTargetSessionStatus.RUNNING);
        session.setBudgetAmountUsdt(new BigDecimal("50"));
        session.setTargetProfitUsdt(new BigDecimal("10"));
        session.setTraceId("trace-turn-off");
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        session.setConfigSnapshotJson("{}");

        List<BudgetTargetSession> savedSnapshots = new ArrayList<>();
        List<BudgetTargetSessionEvent> savedEvents = new ArrayList<>();
        when(sessionRepository.findActiveForUpdate(any())).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(BudgetTargetSession.class))).thenAnswer(invocation -> {
            BudgetTargetSession saved = invocation.getArgument(0);
            savedSnapshots.add(copySession(saved));
            return saved;
        });
        when(eventRepository.save(any(BudgetTargetSessionEvent.class))).thenAnswer(invocation -> {
            BudgetTargetSessionEvent event = invocation.getArgument(0);
            savedEvents.add(event);
            return event;
        });

        BudgetTargetAutoExecutionLifecycleService service = new BudgetTargetAutoExecutionLifecycleService(
                settingsProvider,
                sessionRepository,
                eventRepository,
                preflightService,
                streamPublisher,
                new ObjectMapper().findAndRegisterModules());

        BudgetTargetAutoExecutionStateRequestDTO request = new BudgetTargetAutoExecutionStateRequestDTO();
        request.setCommand(BudgetTargetAutoExecutionStateRequestDTO.Command.TURN_OFF);

        service.applyCommand(request, "tester");

        BudgetTargetSession saved = savedSnapshots.getLast();
        assertEquals(BudgetTargetSessionStatus.STOPPING, saved.getStatus());
        assertEquals(BudgetTargetSessionCompletionReason.OPERATOR_STOPPED, saved.getCompletionReason());
        assertEquals("OPERATOR_STOPPED", saved.getStopReason());
        assertEquals("tester", saved.getStoppedBy());
        BudgetTargetSessionEvent stopEvent = savedEvents.getLast();
        assertEquals("STOP_REQUESTED", stopEvent.getEventType());
        assertEquals("OPERATOR_STOPPED", stopEvent.getReasonCode());
        assertEquals("Operator turned budget-target auto-execution OFF.", stopEvent.getNotes());
    }

    @Test
    void recordExecutionFailureFailsSessionAfterThreshold() {
        ControlCenterSettingsProvider settingsProvider = mock(ControlCenterSettingsProvider.class);
        BudgetTargetSessionRepository sessionRepository = mock(BudgetTargetSessionRepository.class);
        BudgetTargetSessionEventRepository eventRepository = mock(BudgetTargetSessionEventRepository.class);
        LiveTradingPreflightService preflightService = mock(LiveTradingPreflightService.class);
        BudgetTargetSessionStreamPublisher streamPublisher = mock(BudgetTargetSessionStreamPublisher.class);
        ControlCenterConfig.BudgetTargetAutoExecution config = new ControlCenterConfig.BudgetTargetAutoExecution();
        config.setEnabled(false);
        when(settingsProvider.getBudgetTargetAutoExecutionSettings()).thenReturn(config);
        when(settingsProvider.patchOperational(any(), any(), any())).thenReturn(new ControlCenterConfig());
        when(sessionRepository.save(any(BudgetTargetSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any(BudgetTargetSessionEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BudgetTargetAutoExecutionLifecycleService service = new BudgetTargetAutoExecutionLifecycleService(
                settingsProvider,
                sessionRepository,
                eventRepository,
                preflightService,
                streamPublisher,
                new ObjectMapper().findAndRegisterModules());

        BudgetTargetSession session = new BudgetTargetSession();
        session.setStatus(BudgetTargetSessionStatus.STOPPING);
        session.setOpenedPositionsTotal(1);
        session.setActivePositionsCount(1);
        session.setExecutionFailureCount(2);
        session.setTraceId("trace-failure-threshold");
        session.setBudgetAmountUsdt(new BigDecimal("50"));
        session.setTargetProfitUsdt(new BigDecimal("10"));
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        session.setConfigSnapshotJson("{}");

        LiveTradeExecution execution = new LiveTradeExecution();
        execution.setCreatedAt(Instant.now());

        BudgetTargetSession updated = service.recordExecutionFailure(
                session,
                execution,
                "UPSTREAM_TIMEOUT",
                "Close-all submission timed out.",
                java.util.Map.of("attempt", 3));

        assertEquals(BudgetTargetSessionStatus.FAILED, updated.getStatus());
        assertEquals(BudgetTargetSessionCompletionReason.EXECUTION_FAILURE_THRESHOLD, updated.getCompletionReason());
        assertEquals("EXECUTION_FAILURE_THRESHOLD", updated.getStopReason());
        assertEquals(3, updated.getExecutionFailureCount());
        assertEquals("UPSTREAM_TIMEOUT", updated.getLastErrorCode());
    }

    private BudgetTargetSession copySession(BudgetTargetSession source) {
        BudgetTargetSession copy = new BudgetTargetSession();
        copy.setStatus(source.getStatus());
        copy.setBudgetAmountUsdt(source.getBudgetAmountUsdt());
        copy.setTargetProfitUsdt(source.getTargetProfitUsdt());
        copy.setMaxConcurrentPositions(source.getMaxConcurrentPositions());
        copy.setCompletionReason(source.getCompletionReason());
        copy.setStopRequested(source.isStopRequested());
        copy.setStoppedBy(source.getStoppedBy());
        copy.setStopReason(source.getStopReason());
        copy.setLastErrorCode(source.getLastErrorCode());
        copy.setLastErrorMessage(source.getLastErrorMessage());
        copy.setConfigSnapshotJson(source.getConfigSnapshotJson());
        return copy;
    }
}
