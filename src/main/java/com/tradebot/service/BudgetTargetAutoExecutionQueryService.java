package com.tradebot.service;

import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.dto.BudgetTargetAutoExecutionStateDTO;
import com.tradebot.dto.BudgetTargetSessionDTO;
import com.tradebot.dto.BudgetTargetSessionEventDTO;
import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.BudgetTargetSessionEvent;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.repository.BudgetTargetSessionEventRepository;
import com.tradebot.repository.BudgetTargetSessionRepository;
import com.tradebot.repository.LiveTradeExecutionEventRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BudgetTargetAutoExecutionQueryService {

    private static final List<LiveTradeExecutionState> ACTIVE_EXECUTION_STATES = Arrays.stream(LiveTradeExecutionState.values())
            .filter(LiveTradeExecutionState::isActive)
            .toList();
    private static final Set<String> OPERATOR_VISIBLE_STOP_EVENT_TYPES = Set.of(
            "SESSION_FAILED",
            "SESSION_STOPPED_WITH_ERROR",
            "SESSION_COMPLETED",
            "STOP_REQUESTED",
            "TARGET_REACHED",
            "SESSION_RECOVERY_FAILED",
            "SESSION_EXECUTION_FAILURE");
    private static final Set<String> BLOCKER_EVENT_TYPES = Set.of(
            "SESSION_BLOCKED",
            "ACTIVE_LIMIT_REACHED",
            "RECOMMENDATION_SKIPPED",
            "BANKROLL_EXHAUSTED",
            "SCAN_FAILED",
            "SESSION_EXECUTION_FAILURE",
            "EXECUTION_PNL_UNRESOLVED");

    private final ControlCenterSettingsProvider controlCenterSettingsProvider;
    private final BudgetTargetSessionRepository budgetTargetSessionRepository;
    private final BudgetTargetSessionEventRepository budgetTargetSessionEventRepository;
    private final LiveTradeExecutionRepository liveTradeExecutionRepository;
    private final LiveTradeExecutionEventRepository liveTradeExecutionEventRepository;
    private final LiveTradingMapper liveTradingMapper;
    private final BudgetTargetAutoExecutionLifecycleService lifecycleService;
    private final ExchangeSyncSnapshotService exchangeSyncSnapshotService;

    @Transactional(readOnly = true)
    public BudgetTargetAutoExecutionStateDTO getState() {
        ControlCenterConfig.BudgetTargetAutoExecution config = controlCenterSettingsProvider.getBudgetTargetAutoExecutionSettings();
        BudgetTargetAutoExecutionStateDTO dto = new BudgetTargetAutoExecutionStateDTO();
        dto.getConfig().setEnabled(config.isEnabled());
        dto.getConfig().setArmed(config.isArmed());
        dto.getConfig().setReadOnly(config.isReadOnly());
        dto.getConfig().setMaxConcurrentPositions(config.getMaxConcurrentPositions());
        dto.getConfig().setDefaultBudgetUsdt(config.getDefaultBudgetUsdt());
        dto.getConfig().setDefaultTargetProfitUsdt(config.getDefaultTargetProfitUsdt());
        dto.getConfig().setAllowNewSessionStart(config.isAllowNewSessionStart());
        dto.getConfig().setAllowCloseAllOnTarget(config.isAllowCloseAllOnTarget());
        dto.getConfig().setKillSwitch(config.isKillSwitch());
        dto.getConfig().setRequireBinanceHealthPass(config.isRequireBinanceHealthPass());
        dto.getConfig().setRequireOperatorConfirmationForStop(config.isRequireOperatorConfirmationForStop());
        dto.getConfig().setSessionTimeoutMinutes(config.getSessionTimeoutMinutes());

        BudgetTargetSession active = lifecycleService.findActiveSession().orElse(null);
        BudgetTargetSession latest = budgetTargetSessionRepository.findFirstByOrderByCreatedAtDesc().orElse(null);

        dto.setActiveSession(toSessionDto(active));
        dto.setLatestSession(toSessionDto(latest));
        BudgetTargetSession source = active != null ? active : latest;
        if (source != null) {
            dto.setSyncHealth(exchangeSyncSnapshotService.summarizeSession(source.getId()));
            dto.setOrders(listOrders(source.getId()));
            dto.setEvents(listEvents(source.getId()));
            DerivedBlockedReason blocker = resolvePrimaryBlockedReason(source, dto.getEvents(), dto.getSyncHealth());
            dto.setPrimaryBlockedReasonCode(blocker.code());
            dto.setPrimaryBlockedReasonMessage(blocker.message());
            dto.setPrimaryBlockedReasonSource(blocker.source());
        }
        dto.setServerTime(Instant.now());
        return dto;
    }

    @Transactional(readOnly = true)
    public BudgetTargetSessionDTO toSessionDto(BudgetTargetSession session) {
        if (session == null) {
            return null;
        }
        BudgetTargetSessionDTO dto = new BudgetTargetSessionDTO();
        dto.setId(session.getId());
        dto.setStatus(session.getStatus().name());
        dto.setStopReason(session.getStopReason());
        dto.setCompletionReason(session.getCompletionReason() != null ? session.getCompletionReason().name() : null);
        dto.setBudgetAmountUsdt(session.getBudgetAmountUsdt());
        dto.setTargetProfitUsdt(session.getTargetProfitUsdt());
        dto.setSessionBudgetUsdt(session.getBudgetAmountUsdt());
        dto.setFinalTargetNetProfitUsdt(session.getTargetProfitUsdt());
        dto.setRealizedNetPnlUsdt(session.getRealizedNetPnlUsdt());
        dto.setUnrealizedNetPnlUsdt(session.getUnrealizedNetPnlUsdt());
        dto.setMaxConcurrentPositions(session.getMaxConcurrentPositions());
        dto.setActivePositionsCount(session.getActivePositionsCount());
        dto.setOpenedPositionsTotal(session.getOpenedPositionsTotal());
        dto.setClosedPositionsTotal(session.getClosedPositionsTotal());
        dto.setActiveTradeLimit(session.getMaxConcurrentPositions());
        dto.setActiveTradeCount(liveTradeExecutionRepository.countBySession_IdAndExecutionStatusIn(
                session.getId(), ACTIVE_EXECUTION_STATES));
        dto.setOpenedTradeCount(liveTradeExecutionRepository.countBySession_Id(session.getId()));
        dto.setPendingScanRunId(session.getPendingScanRunId());
        dto.setStopRequested(session.isStopRequested());
        dto.setLastErrorCode(session.getLastErrorCode());
        dto.setLastErrorMessage(session.getLastErrorMessage());
        dto.setFailureReasonCode(session.getLastErrorCode());
        dto.setFailureReasonMessage(session.getLastErrorMessage());
        dto.setExecutionFailureCount(session.getExecutionFailureCount());
        dto.setStartedAt(session.getStartedAt());
        dto.setEndedAt(session.getEndedAt());
        dto.setCompletedAt(session.getEndedAt());
        dto.setUpdatedAt(session.getUpdatedAt());
        dto.setRemainingBankrollUsdt(resolveRemainingBankroll(session));
        dto.setStopReasonMessage(resolveStopReasonMessage(session));
        dto.setSyncHealth(exchangeSyncSnapshotService.summarizeSession(session.getId()));
        return dto;
    }

    @Transactional(readOnly = true)
    public List<LiveTradeExecutionDTO> listOrders(UUID sessionId) {
        return liveTradeExecutionRepository.findTop20BySession_IdOrderByCreatedAtDesc(sessionId).stream()
                .map(this::toExecutionDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BudgetTargetSessionEventDTO> listEvents(UUID sessionId) {
        return budgetTargetSessionEventRepository.findTop50BySession_IdOrderByEventTsDesc(sessionId).stream()
                .map(this::toEventDto)
                .toList();
    }

    private LiveTradeExecutionDTO toExecutionDto(LiveTradeExecution execution) {
        LiveTradeExecutionDTO dto = liveTradingMapper.toDetail(
                execution,
                liveTradeExecutionEventRepository.findByExecution_IdOrderByEventTsAsc(execution.getId()));
        dto.setSyncHealth(exchangeSyncSnapshotService.summarizeExecution(execution.getId()));
        return dto;
    }

    private BudgetTargetSessionEventDTO toEventDto(BudgetTargetSessionEvent event) {
        BudgetTargetSessionEventDTO dto = new BudgetTargetSessionEventDTO();
        dto.setId(event.getId());
        dto.setExecutionId(event.getExecution() != null ? event.getExecution().getId() : null);
        dto.setEventCategory(event.getEventCategory() != null ? event.getEventCategory().name() : null);
        dto.setSeverity(event.getSeverity() != null ? event.getSeverity().name() : null);
        dto.setActor(event.getActor());
        dto.setEventType(event.getEventType());
        dto.setEventStatus(event.getEventStatus());
        dto.setBefore(lifecycleService.readPayload(event.getBeforeJson()));
        dto.setAfter(lifecycleService.readPayload(event.getAfterJson()));
        dto.setNotes(event.getNotes());
        dto.setTraceId(event.getTraceId());
        dto.setEventTs(event.getEventTs());
        dto.setMessage(event.getNotes());
        dto.setReasonCode(event.getReasonCode());
        dto.setPayload(lifecycleService.readPayload(event.getAfterJson()));
        dto.setCreatedAt(event.getEventTs());
        return dto;
    }

    private BigDecimal resolveRemainingBankroll(BudgetTargetSession session) {
        List<LiveTradeExecution> activeExecutions = liveTradeExecutionRepository
                .findBySession_IdAndExecutionStatusInOrderByCreatedAtAsc(session.getId(), ACTIVE_EXECUTION_STATES);
        BigDecimal reserved = activeExecutions.stream()
                .map(LiveTradeExecution::getReservedMarginUsdt)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return session.getBudgetAmountUsdt()
                .add(session.getRealizedNetPnlUsdt() == null ? BigDecimal.ZERO : session.getRealizedNetPnlUsdt())
                .subtract(reserved);
    }

    private String resolveStopReasonMessage(BudgetTargetSession session) {
        List<BudgetTargetSessionEvent> recentEvents = budgetTargetSessionEventRepository
                .findTop50BySession_IdOrderByEventTsDesc(session.getId());

        for (BudgetTargetSessionEvent event : recentEvents) {
            if (event.getNotes() == null || event.getNotes().isBlank()) {
                continue;
            }
            if (OPERATOR_VISIBLE_STOP_EVENT_TYPES.contains(event.getEventType())) {
                return event.getNotes();
            }
            if (matchesReason(event, session)) {
                return event.getNotes();
            }
        }

        if (session.getLastErrorMessage() != null && !session.getLastErrorMessage().isBlank()) {
            return session.getLastErrorMessage();
        }
        if (session.getStopReason() != null && !session.getStopReason().isBlank()) {
            return session.getStopReason();
        }
        if (session.getCompletionReason() != null) {
            return session.getCompletionReason().name();
        }
        return null;
    }

    private boolean matchesReason(BudgetTargetSessionEvent event, BudgetTargetSession session) {
        Set<String> candidates = new LinkedHashSet<>();
        if (session.getStopReason() != null) {
            candidates.add(session.getStopReason());
        }
        if (session.getCompletionReason() != null) {
            candidates.add(session.getCompletionReason().name());
        }
        return event.getReasonCode() != null && candidates.contains(event.getReasonCode());
    }

    private DerivedBlockedReason resolvePrimaryBlockedReason(BudgetTargetSession session,
            List<BudgetTargetSessionEventDTO> events,
            com.tradebot.dto.BudgetTargetSyncHealthDTO syncHealth) {
        if (session == null) {
            return DerivedBlockedReason.none();
        }
        if (session.getPendingScanRunId() != null) {
            return new DerivedBlockedReason(
                    "PENDING_SCAN",
                    "Waiting for session-owned scan " + session.getPendingScanRunId()
                            + " to finish before opening a new trade.",
                    "pending-scan");
        }
        if (syncHealth != null && syncHealth.isGateNewTrades()) {
            return new DerivedBlockedReason(
                    syncHealth.getGateReasonCode(),
                    syncHealth.getGateReasonMessage(),
                    "sync");
        }
        boolean ignoreRecoveredSyncFailure = exchangeSyncSnapshotService.isSyncHealthGateCode(session.getLastErrorCode())
                && (syncHealth == null || !syncHealth.isGateNewTrades());
        if (!ignoreRecoveredSyncFailure
                && (session.getLastErrorCode() != null || session.getLastErrorMessage() != null)) {
            return new DerivedBlockedReason(
                    session.getLastErrorCode(),
                    session.getLastErrorMessage(),
                    "session");
        }
        String stopReasonMessage = resolveStopReasonMessage(session);
        if (session.getStopReason() != null || stopReasonMessage != null) {
            return new DerivedBlockedReason(
                    session.getStopReason(),
                    stopReasonMessage,
                    "session");
        }
        for (BudgetTargetSessionEventDTO event : events) {
            if (!BLOCKER_EVENT_TYPES.contains(event.getEventType())) {
                continue;
            }
            return new DerivedBlockedReason(
                    event.getReasonCode(),
                    event.getMessage(),
                    "event");
        }
        return DerivedBlockedReason.none();
    }

    private record DerivedBlockedReason(String code, String message, String source) {
        private static DerivedBlockedReason none() {
            return new DerivedBlockedReason(null, null, null);
        }
    }
}
