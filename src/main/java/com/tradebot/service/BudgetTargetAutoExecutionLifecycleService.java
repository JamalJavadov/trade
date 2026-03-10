package com.tradebot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.dto.BudgetTargetAutoExecutionStateRequestDTO;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.BudgetTargetSessionCompletionReason;
import com.tradebot.entity.BudgetTargetSessionEvent;
import com.tradebot.entity.BudgetTargetSessionStatus;
import com.tradebot.entity.BudgetTargetSessionStopReason;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.repository.BudgetTargetSessionEventRepository;
import com.tradebot.repository.BudgetTargetSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BudgetTargetAutoExecutionLifecycleService {

    private static final String LIVE_EXECUTION_PERMISSION = "live.execution.enabled";
    private static final String AUTO_TARGET_FEATURE_DISABLED = "AUTO_TARGET_FEATURE_DISABLED";
    private static final String AUTO_TARGET_KILL_SWITCH = "AUTO_TARGET_KILL_SWITCH";
    private static final String AUTO_TARGET_READ_ONLY = "AUTO_TARGET_READ_ONLY";
    private static final String AUTO_TARGET_NEW_SESSION_START_DISABLED = "AUTO_TARGET_NEW_SESSION_START_DISABLED";
    private static final String AUTO_TARGET_BINANCE_HEALTH_BLOCKED = "AUTO_TARGET_BINANCE_HEALTH_BLOCKED";
    private static final String SCAN_SAFE_MODE = "SCAN_SAFE_MODE";
    private static final int EXECUTION_FAILURE_THRESHOLD = 3;
    private static final EnumSet<BudgetTargetSessionStatus> NON_TERMINAL_SESSION_STATUSES = EnumSet.of(
            BudgetTargetSessionStatus.DRAFT,
            BudgetTargetSessionStatus.ARMED,
            BudgetTargetSessionStatus.RUNNING,
            BudgetTargetSessionStatus.TARGET_REACHED,
            BudgetTargetSessionStatus.STOPPING);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ControlCenterSettingsProvider controlCenterSettingsProvider;
    private final BudgetTargetSessionRepository budgetTargetSessionRepository;
    private final BudgetTargetSessionEventRepository budgetTargetSessionEventRepository;
    private final LiveTradingPreflightService liveTradingPreflightService;
    private final BudgetTargetSessionStreamPublisher sessionStreamPublisher;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Optional<BudgetTargetSession> findActiveSession() {
        return budgetTargetSessionRepository.findFirstByStatusInOrderByCreatedAtDesc(NON_TERMINAL_SESSION_STATUSES);
    }

    @Transactional
    public void applyCommand(BudgetTargetAutoExecutionStateRequestDTO request, String operatorId) {
        if (request == null || request.getCommand() == null) {
            throw new IllegalArgumentException("command is required");
        }

        switch (request.getCommand()) {
            case TURN_ON -> turnOn(
                    operatorId,
                    request.getReason(),
                    request.getBudgetAmountUsdt(),
                    request.getTargetProfitUsdt());
            case TURN_OFF -> turnOff(operatorId, request.getReason(), Boolean.TRUE.equals(request.getConfirmStop()));
        }
    }

    @Transactional
    public void syncRuntimeWithControlCenter() {
        ControlCenterConfig configSnapshot = controlCenterSettingsProvider.getConfigSnapshot();
        ControlCenterConfig.BudgetTargetAutoExecution config = configSnapshot.getBudgetTargetAutoExecution();
        BudgetTargetSession session = budgetTargetSessionRepository.findActiveForUpdate(NON_TERMINAL_SESSION_STATUSES)
                .orElse(null);
        if (session == null) {
            if (config.isArmed()) {
                patchArmedRuntime(false, "system", "budget-target-auto-session-runtime-self-heal");
            }
            return;
        }

        alignSessionRuntimeConfig(session, "system");
        evaluateRuntimeStop(configSnapshot, session, false).ifPresent(decision -> requestStop(
                session,
                decision.reason(),
                "system",
                decision.message()));
    }

    @Transactional
    public BudgetTargetSession markArmed(BudgetTargetSession session, String actor, String message) {
        if (session.getStatus() == BudgetTargetSessionStatus.ARMED) {
            return session;
        }
        Map<String, Object> beforeState = BudgetTargetAuditSupport.sessionSnapshot(session);
        session.setStatus(BudgetTargetSessionStatus.ARMED);
        session.setUpdatedAt(Instant.now());
        session = budgetTargetSessionRepository.save(session);
        appendEvent(session,
                null,
                "SESSION_ARMED",
                message != null ? message : "Budget-target auto-execution session armed and waiting for runtime checks.",
                null,
                payloadOf("armedAt", session.getUpdatedAt()),
                actor,
                beforeState);
        return session;
    }

    @Transactional
    public BudgetTargetSession markRunning(BudgetTargetSession session, String actor, String message) {
        if (session.getStatus() == BudgetTargetSessionStatus.RUNNING) {
            return session;
        }
        Map<String, Object> beforeState = BudgetTargetAuditSupport.sessionSnapshot(session);
        session.setStatus(BudgetTargetSessionStatus.RUNNING);
        session.setLastErrorCode(null);
        session.setLastErrorMessage(null);
        if (session.getStartedAt() == null) {
            session.setStartedAt(Instant.now());
        }
        session.setUpdatedAt(Instant.now());
        session = budgetTargetSessionRepository.save(session);
        appendEvent(session,
                null,
                "SESSION_RUNNING",
                message != null ? message : "Budget-target auto-execution session passed preflight and is RUNNING.",
                null,
                payloadOf("startedAt", session.getStartedAt()),
                actor,
                beforeState);
        return session;
    }

    @Transactional
    public BudgetTargetSession markTargetReached(BudgetTargetSession session, String actor, String message) {
        if (session.getStatus() == BudgetTargetSessionStatus.TARGET_REACHED) {
            return session;
        }
        Map<String, Object> beforeState = BudgetTargetAuditSupport.sessionSnapshot(session);
        session.setStatus(BudgetTargetSessionStatus.TARGET_REACHED);
        session.setCompletionReason(BudgetTargetSessionCompletionReason.TARGET_REACHED);
        session.setStopReason(BudgetTargetSessionStopReason.TARGET_REACHED.name());
        session.setUpdatedAt(Instant.now());
        session = budgetTargetSessionRepository.save(session);
        appendEvent(session,
                null,
                "TARGET_REACHED",
                message != null ? message : "Session hit the realized net PnL target.",
                BudgetTargetSessionCompletionReason.TARGET_REACHED.name(),
                payloadOf(
                        "targetProfitUsdt", session.getTargetProfitUsdt(),
                        "realizedNetPnlUsdt", session.getRealizedNetPnlUsdt(),
                        "targetSatisfiedAt", Instant.now()),
                actor,
                beforeState);
        return session;
    }

    @Transactional
    public BudgetTargetSession requestStop(BudgetTargetSession session,
            BudgetTargetSessionCompletionReason reason,
            String actor,
            String message) {
        if (session.getStatus().isTerminal()) {
            return session;
        }
        if (session.getStatus() == BudgetTargetSessionStatus.STOPPING
                && session.isStopRequested()
                && session.getCompletionReason() == reason) {
            return session;
        }
        Map<String, Object> beforeState = BudgetTargetAuditSupport.sessionSnapshot(session);
        UUID pendingScanRunId = session.getPendingScanRunId();
        session.setCompletionReason(reason);
        session.setStopReason(resolveStopReason(reason).name());
        session.setStopRequested(true);
        session.setStoppedBy(BudgetTargetAuditSupport.normalizeActor(actor));
        session.setStopRequestedAt(Instant.now());
        session.setPendingScanRunId(null);
        session.setUpdatedAt(Instant.now());
        if (session.getStatus() != BudgetTargetSessionStatus.STOPPING) {
            session.setStatus(BudgetTargetSessionStatus.STOPPING);
        }
        session = budgetTargetSessionRepository.save(session);
        appendEvent(session,
                null,
                "STOP_REQUESTED",
                message,
                reason != null ? reason.name() : null,
                payloadOf(
                        "requestedBy", BudgetTargetAuditSupport.normalizeActor(actor),
                        "pendingScanRunId", pendingScanRunId),
                actor,
                beforeState);
        patchArmedRuntime(false, BudgetTargetAuditSupport.normalizeActor(actor), "budget-target-auto-session-stop");
        return session;
    }

    @Transactional
    public BudgetTargetSession updateSessionRollup(BudgetTargetSession session,
            BigDecimal realizedNetPnlUsdt,
            BigDecimal unrealizedNetPnlUsdt,
            int activePositionsCount,
            int openedPositionsTotal,
            int closedPositionsTotal) {
        session.setRealizedNetPnlUsdt(realizedNetPnlUsdt == null ? BigDecimal.ZERO : realizedNetPnlUsdt);
        session.setUnrealizedNetPnlUsdt(unrealizedNetPnlUsdt == null ? BigDecimal.ZERO : unrealizedNetPnlUsdt);
        session.setActivePositionsCount(Math.max(0, activePositionsCount));
        session.setOpenedPositionsTotal(Math.max(0, openedPositionsTotal));
        session.setClosedPositionsTotal(Math.max(0, closedPositionsTotal));
        session.setUpdatedAt(Instant.now());
        return budgetTargetSessionRepository.save(session);
    }

    @Transactional
    public BudgetTargetSession recordExecutionFailure(BudgetTargetSession session,
            LiveTradeExecution execution,
            String errorCode,
            String errorMessage,
            Object payload) {
        if (session == null || session.getStatus().isTerminal()) {
            return session;
        }
        Map<String, Object> beforeState = BudgetTargetAuditSupport.sessionSnapshot(session);
        session.setExecutionFailureCount(session.getExecutionFailureCount() + 1);
        session.setLastErrorCode(errorCode);
        session.setLastErrorMessage(errorMessage);
        session.setUpdatedAt(Instant.now());
        session = budgetTargetSessionRepository.save(session);
        appendEvent(session,
                execution,
                "SESSION_EXECUTION_FAILURE",
                errorMessage,
                errorCode,
                payloadOf(
                        "executionFailureCount", session.getExecutionFailureCount(),
                        "details", payload),
                execution != null ? execution.getOperatorId() : null,
                beforeState);
        if (session.getExecutionFailureCount() >= EXECUTION_FAILURE_THRESHOLD) {
            return failSession(session,
                    BudgetTargetSessionCompletionReason.EXECUTION_FAILURE_THRESHOLD,
                    errorCode != null ? errorCode : BudgetTargetSessionCompletionReason.EXECUTION_FAILURE_THRESHOLD.name(),
                    "Session hit the execution failure threshold after repeated unresolved execution or close failures.");
        }
        return session;
    }

    @Transactional
    public BudgetTargetSession setVisibleFailure(BudgetTargetSession session,
            String errorCode,
            String errorMessage) {
        if (session == null || session.getStatus().isTerminal()) {
            return session;
        }
        session.setLastErrorCode(errorCode);
        session.setLastErrorMessage(errorMessage);
        session.setUpdatedAt(Instant.now());
        return budgetTargetSessionRepository.save(session);
    }

    @Transactional
    public BudgetTargetSession completeSession(BudgetTargetSession session,
            BudgetTargetSessionCompletionReason reason,
            String message) {
        Map<String, Object> beforeState = BudgetTargetAuditSupport.sessionSnapshot(session);
        session.setCompletionReason(reason);
        BudgetTargetSessionStatus terminalStatus = resolveTerminalStatus(session, reason);
        session.setStatus(terminalStatus);
        session.setStopReason(resolveTerminalStopReason(session, reason).name());
        if (session.getEndedAt() == null) {
            session.setEndedAt(Instant.now());
        }
        session.setUpdatedAt(Instant.now());
        session = budgetTargetSessionRepository.save(session);
        appendEvent(session,
                null,
                "SESSION_COMPLETED",
                message,
                session.getStopReason(),
                payloadOf(
                        "completionReason", session.getCompletionReason() != null ? session.getCompletionReason().name() : null,
                        "realizedNetPnlUsdt", session.getRealizedNetPnlUsdt(),
                        "unrealizedNetPnlUsdt", session.getUnrealizedNetPnlUsdt(),
                        "terminalStatus", session.getStatus().name()),
                session.getStoppedBy(),
                beforeState);
        patchArmedRuntime(false, "system", "budget-target-auto-session-complete");
        return session;
    }

    @Transactional
    public BudgetTargetSession stopWithVisibleFailure(BudgetTargetSession session,
            BudgetTargetSessionCompletionReason reason,
            String errorCode,
            String errorMessage) {
        Map<String, Object> beforeState = BudgetTargetAuditSupport.sessionSnapshot(session);
        session.setCompletionReason(reason);
        session.setStatus(resolveTerminalStatus(session, reason));
        session.setStopReason(resolveTerminalStopReason(session, reason).name());
        session.setLastErrorCode(errorCode);
        session.setLastErrorMessage(errorMessage);
        session.setEndedAt(session.getEndedAt() != null ? session.getEndedAt() : Instant.now());
        session.setUpdatedAt(Instant.now());
        session = budgetTargetSessionRepository.save(session);
        appendEvent(session,
                null,
                "SESSION_STOPPED_WITH_ERROR",
                errorMessage,
                errorCode,
                payloadOf(
                        "completionReason", session.getCompletionReason() != null ? session.getCompletionReason().name() : null,
                        "terminalStatus", session.getStatus().name(),
                        "stopReason", session.getStopReason()),
                session.getStoppedBy(),
                beforeState);
        patchArmedRuntime(false, "system", "budget-target-auto-session-stop-visible-failure");
        return session;
    }

    @Transactional
    public BudgetTargetSession failSession(BudgetTargetSession session,
            BudgetTargetSessionCompletionReason reason,
            String errorCode,
            String errorMessage) {
        Map<String, Object> beforeState = BudgetTargetAuditSupport.sessionSnapshot(session);
        session.setCompletionReason(reason);
        session.setStatus(BudgetTargetSessionStatus.FAILED);
        session.setStopReason(resolveStopReason(reason).name());
        session.setLastErrorCode(errorCode);
        session.setLastErrorMessage(errorMessage);
        session.setEndedAt(session.getEndedAt() != null ? session.getEndedAt() : Instant.now());
        session.setUpdatedAt(Instant.now());
        session = budgetTargetSessionRepository.save(session);
        appendEvent(session,
                null,
                "SESSION_FAILED",
                errorMessage,
                errorCode,
                payloadOf(
                        "completionReason", session.getCompletionReason() != null ? session.getCompletionReason().name() : null,
                        "stopReason", session.getStopReason()),
                session.getStoppedBy(),
                beforeState);
        patchArmedRuntime(false, "system", "budget-target-auto-session-failed");
        return session;
    }

    @Transactional
    public BudgetTargetSession setPendingScanRun(BudgetTargetSession session, UUID scanRunId, String message) {
        Map<String, Object> beforeState = BudgetTargetAuditSupport.sessionSnapshot(session);
        session.setPendingScanRunId(scanRunId);
        session.setUpdatedAt(Instant.now());
        session = budgetTargetSessionRepository.save(session);
        appendEvent(session,
                null,
                "SCAN_REQUESTED",
                message,
                null,
                payloadOf("scanRunId", scanRunId),
                null,
                beforeState);
        return session;
    }

    @Transactional
    public BudgetTargetSession clearPendingScanRun(BudgetTargetSession session,
            String eventType,
            String message,
            Object payload) {
        Map<String, Object> beforeState = BudgetTargetAuditSupport.sessionSnapshot(session);
        session.setPendingScanRunId(null);
        session.setUpdatedAt(Instant.now());
        session = budgetTargetSessionRepository.save(session);
        appendEvent(session,
                null,
                eventType,
                message,
                null,
                payload,
                null,
                beforeState);
        return session;
    }

    @Transactional
    public BudgetTargetSession appendSessionEvent(BudgetTargetSession session,
            String eventType,
            String message,
            String reasonCode,
            Object payload) {
        return appendEvent(session,
                null,
                eventType,
                message,
                reasonCode,
                payload,
                null,
                BudgetTargetAuditSupport.sessionSnapshot(session));
    }

    @Transactional
    public BudgetTargetSession appendSessionEvent(BudgetTargetSession session,
            LiveTradeExecution execution,
            String eventType,
            String message,
            String reasonCode,
            Object payload) {
        return appendEvent(session,
                execution,
                eventType,
                message,
                reasonCode,
                payload,
                execution != null ? execution.getOperatorId() : null,
                BudgetTargetAuditSupport.sessionSnapshot(session));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> readPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, MAP_TYPE);
        } catch (Exception ex) {
            return Map.of("raw", payloadJson);
        }
    }

    public boolean isSessionTimedOut(BudgetTargetSession session, ControlCenterConfig.BudgetTargetAutoExecution config) {
        if (session == null || session.getStatus().isTerminal()) {
            return false;
        }
        Instant reference = session.getStartedAt() != null ? session.getStartedAt() : session.getCreatedAt();
        if (reference == null) {
            return false;
        }
        return reference.plus(Duration.ofMinutes(config.getSessionTimeoutMinutes())).isBefore(Instant.now());
    }

    public BudgetTargetSession attemptRunTransitionIfReady(BudgetTargetSession session, String actor, String message) {
        if (session.getStatus() != BudgetTargetSessionStatus.ARMED) {
            return session;
        }
        StartGateDecision gateDecision = evaluateStartGate(controlCenterSettingsProvider.getConfigSnapshot(), true);
        if (!gateDecision.ready()) {
            return noteStartBlocked(session, gateDecision);
        }
        return markRunning(session, actor, message);
    }

    private void turnOn(String operatorId,
            String reason,
            BigDecimal requestedBudgetAmountUsdt,
            BigDecimal requestedTargetProfitUsdt) {
        String actor = BudgetTargetAuditSupport.normalizeActor(operatorId);
        ControlCenterConfig configSnapshot = controlCenterSettingsProvider.getConfigSnapshot();
        ControlCenterConfig.BudgetTargetAutoExecution config = configSnapshot.getBudgetTargetAutoExecution();
        if (!config.isEnabled()) {
            throw new IllegalStateException("Budget-target auto-execution is disabled in Control Center.");
        }
        if (!config.isAllowNewSessionStart()) {
            throw new IllegalStateException("Budget-target auto-execution new session starts are disabled.");
        }
        if (config.isKillSwitch()) {
            throw new IllegalStateException("Budget-target auto-execution kill switch is enabled.");
        }
        if (findActiveSession().isPresent()) {
            throw new IllegalStateException("A budget-target auto-execution session is already active.");
        }

        BigDecimal sessionBudgetUsdt = resolveRequestedAmount(
                requestedBudgetAmountUsdt,
                config.getDefaultBudgetUsdt(),
                "budgetAmountUsdt");
        BigDecimal targetProfitUsdt = resolveRequestedAmount(
                requestedTargetProfitUsdt,
                config.getDefaultTargetProfitUsdt(),
                "targetProfitUsdt");

        patchArmedRuntime(true, actor, reason == null ? "budget-target-auto-session-turn-on" : reason);
        ControlCenterConfig armedSnapshot = controlCenterSettingsProvider.getConfigSnapshot();
        BudgetTargetSession session = createDraftSession(actor, reason, sessionBudgetUsdt, targetProfitUsdt, armedSnapshot);
        session = markArmed(session, actor, "Operator turned budget-target auto-execution ON.");
        attemptRunTransitionIfReady(session, actor, "Budget-target auto-execution passed current preflight checks and moved to RUNNING.");
    }

    private void turnOff(String operatorId, String reason, boolean confirmStop) {
        String actor = BudgetTargetAuditSupport.normalizeActor(operatorId);
        ControlCenterConfig.BudgetTargetAutoExecution config = controlCenterSettingsProvider.getBudgetTargetAutoExecutionSettings();
        if (config.isRequireOperatorConfirmationForStop() && !confirmStop) {
            throw new IllegalStateException("confirmStop=true is required before turning budget-target auto-execution OFF.");
        }
        patchArmedRuntime(false, actor, reason == null ? "budget-target-auto-session-turn-off" : reason);

        BudgetTargetSession session = budgetTargetSessionRepository.findActiveForUpdate(NON_TERMINAL_SESSION_STATUSES)
                .orElse(null);
        if (session == null) {
            return;
        }
        requestStop(session,
                BudgetTargetSessionCompletionReason.OPERATOR_STOPPED,
                actor,
                "Operator turned budget-target auto-execution OFF.");
    }

    private BudgetTargetSession createDraftSession(String actor,
            String startReason,
            BigDecimal sessionBudgetUsdt,
            BigDecimal targetProfitUsdt,
            ControlCenterConfig configSnapshot) {
        ControlCenterConfig.BudgetTargetAutoExecution autoConfig = configSnapshot.getBudgetTargetAutoExecution();
        Instant now = Instant.now();

        BudgetTargetSession session = new BudgetTargetSession();
        session.setStatus(BudgetTargetSessionStatus.DRAFT);
        session.setBudgetAmountUsdt(sessionBudgetUsdt);
        session.setTargetProfitUsdt(targetProfitUsdt);
        session.setRealizedNetPnlUsdt(BigDecimal.ZERO);
        session.setUnrealizedNetPnlUsdt(BigDecimal.ZERO);
        session.setMaxConcurrentPositions(ControlCenterConfig.BudgetTargetAutoExecution.LOCKED_MAX_CONCURRENT_POSITIONS);
        session.setExecutionFailureCount(0);
        session.setStartedBy(actor);
        session.setTraceId(UUID.randomUUID().toString());
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setConfigSnapshotJson(writeJson(buildSessionConfigSnapshot(session, configSnapshot, actor)));
        session = budgetTargetSessionRepository.save(session);
        appendEvent(session,
                null,
                "SESSION_DRAFTED",
                "Created a budget-target auto-execution session draft.",
                null,
                payloadOf(
                        "startReason", startReason,
                        "sessionBudgetUsdt", sessionBudgetUsdt,
                        "targetProfitUsdt", targetProfitUsdt,
                        "defaultBudgetUsdt", autoConfig.getDefaultBudgetUsdt(),
                        "defaultTargetProfitUsdt", autoConfig.getDefaultTargetProfitUsdt(),
                        "maxConcurrentPositions", autoConfig.getMaxConcurrentPositions(),
                        "controlCenterVersion", controlCenterSettingsProvider.getCurrentVersion()),
                actor,
                Map.of());
        return session;
    }

    private BigDecimal resolveRequestedAmount(BigDecimal requestedValue, BigDecimal defaultValue, String fieldName) {
        BigDecimal resolved = requestedValue != null ? requestedValue : defaultValue;
        if (resolved == null || resolved.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than 0");
        }
        return resolved;
    }

    private BudgetTargetSession noteStartBlocked(BudgetTargetSession session, StartGateDecision gateDecision) {
        boolean changed = !Objects.equals(session.getLastErrorCode(), gateDecision.code())
                || !Objects.equals(session.getLastErrorMessage(), gateDecision.message());
        Map<String, Object> beforeState = BudgetTargetAuditSupport.sessionSnapshot(session);
        session.setLastErrorCode(gateDecision.code());
        session.setLastErrorMessage(gateDecision.message());
        session.setUpdatedAt(Instant.now());
        session = budgetTargetSessionRepository.save(session);
        if (changed) {
            appendEvent(session,
                    null,
                    "SESSION_BLOCKED",
                    gateDecision.message(),
                    gateDecision.code(),
                    gateDecision.payload(),
                    null,
                    beforeState);
        }
        return session;
    }

    private void alignSessionRuntimeConfig(BudgetTargetSession session, String actor) {
        int lockedMaxConcurrentPositions = ControlCenterConfig.BudgetTargetAutoExecution.LOCKED_MAX_CONCURRENT_POSITIONS;
        if (session.getMaxConcurrentPositions() == lockedMaxConcurrentPositions) {
            return;
        }
        Map<String, Object> beforeState = BudgetTargetAuditSupport.sessionSnapshot(session);
        session.setMaxConcurrentPositions(lockedMaxConcurrentPositions);
        session.setUpdatedAt(Instant.now());
        budgetTargetSessionRepository.save(session);
        appendEvent(session,
                null,
                "SESSION_RUNTIME_CONFIG_UPDATED",
                "Applied updated maxConcurrentPositions from Control Center runtime config.",
                null,
                payloadOf(
                        "updatedBy", BudgetTargetAuditSupport.normalizeActor(actor),
                        "maxConcurrentPositions", lockedMaxConcurrentPositions),
                actor,
                beforeState);
    }

    private StartGateDecision evaluateStartGate(ControlCenterConfig config, boolean includeHealthCheck) {
        ControlCenterConfig.BudgetTargetAutoExecution autoConfig = config.getBudgetTargetAutoExecution();
        if (!autoConfig.isEnabled()) {
            return blocked(AUTO_TARGET_FEATURE_DISABLED,
                    "Budget-target auto-execution is disabled in Control Center.",
                    payloadOf("configPath", "budgetTargetAutoExecution.enabled"));
        }
        if (autoConfig.isKillSwitch()) {
            return blocked(AUTO_TARGET_KILL_SWITCH,
                    "Budget-target auto-execution kill switch is enabled.",
                    payloadOf("configPath", "budgetTargetAutoExecution.killSwitch"));
        }
        if (autoConfig.isReadOnly()) {
            return blocked(AUTO_TARGET_READ_ONLY,
                    "Budget-target auto-execution is in read-only mode and cannot submit orders.",
                    payloadOf("configPath", "budgetTargetAutoExecution.readOnly"));
        }
        if (config.getLiveExecution().isReadOnly()) {
            return blocked(LiveTradingBlockerCodes.BOT_READ_ONLY,
                    "Live execution is in READ-ONLY mode.",
                    payloadOf("configPath", "liveExecution.readOnly"));
        }
        if (!controlCenterSettingsProvider.can(LIVE_EXECUTION_PERMISSION)) {
            return blocked(LiveTradingBlockerCodes.LIVE_EXECUTION_DISABLED,
                    "Live execution capability is disabled in Control Center.",
                    payloadOf("permissionKey", LIVE_EXECUTION_PERMISSION));
        }
        if (config.getScan().isSafeMode()) {
            return blocked(SCAN_SAFE_MODE,
                    "Auto-execution cannot start while scan.safeMode is enabled.",
                    payloadOf("configPath", "scan.safeMode"));
        }
        if (!autoConfig.isAllowNewSessionStart()) {
            return blocked(AUTO_TARGET_NEW_SESSION_START_DISABLED,
                    "Budget-target auto-execution new session starts are disabled.",
                    payloadOf("configPath", "budgetTargetAutoExecution.allowNewSessionStart"));
        }
        if (includeHealthCheck && autoConfig.isRequireBinanceHealthPass()) {
            LiveTradingPreflightDTO health = liveTradingPreflightService.evaluateHealth("BTCUSDT", null);
            if (!health.isExecutable()) {
                String message = health.getSummary().getPrimaryBlockerMessage();
                return blocked(
                        health.getSummary().getPrimaryBlockerCode() != null
                                ? health.getSummary().getPrimaryBlockerCode()
                                : AUTO_TARGET_BINANCE_HEALTH_BLOCKED,
                        message != null ? message : "Live execution runtime is not ready.",
                        payloadOf(
                                "configPath", "budgetTargetAutoExecution.requireBinanceHealthPass",
                                "summary", payloadOf(
                                        "primaryBlockerCode", health.getSummary().getPrimaryBlockerCode(),
                                        "primaryBlockerMessage", health.getSummary().getPrimaryBlockerMessage())));
            }
        }
        return new StartGateDecision(true, null, null, Map.of());
    }

    private void patchArmedRuntime(boolean armed, String actor, String reason) {
        ControlCenterConfig.BudgetTargetAutoExecution current = controlCenterSettingsProvider.getBudgetTargetAutoExecutionSettings();
        if (current.isArmed() == armed) {
            return;
        }
        ObjectNode patch = objectMapper.createObjectNode();
        patch.putObject("budgetTargetAutoExecution").put("armed", armed);
        controlCenterSettingsProvider.patchOperational(patch, reason, BudgetTargetAuditSupport.normalizeActor(actor));
    }

    private BudgetTargetSession appendEvent(BudgetTargetSession session,
            LiveTradeExecution execution,
            String eventType,
            String message,
            String reasonCode,
            Object payload,
            String actor,
            Map<String, Object> beforeState) {
        BudgetTargetSessionEvent event = new BudgetTargetSessionEvent();
        event.setSession(session);
        event.setExecution(execution);
        event.setEventType(eventType);
        event.setEventStatus(session.getStatus().name());
        event.setNotes(message);
        event.setReasonCode(reasonCode);
        event.setTraceId(session.getTraceId());
        event.setEventCategory(BudgetTargetAuditSupport.categoryForSessionEvent());
        event.setSeverity(BudgetTargetAuditSupport.severityForSessionEvent(eventType));
        event.setActor(BudgetTargetAuditSupport.normalizeActor(actor));
        event.setBeforeJson(writeJson(beforeState == null ? Map.of() : beforeState));
        event.setAfterJson(writeJson(BudgetTargetAuditSupport.sessionEventState(session, actor, reasonCode, payload)));
        event.setEventTs(Instant.now());
        BudgetTargetSessionEvent saved = budgetTargetSessionEventRepository.save(event);
        sessionStreamPublisher.publish(saved);
        return session;
    }

    private Map<String, Object> buildSessionConfigSnapshot(BudgetTargetSession session,
            ControlCenterConfig configSnapshot,
            String actor) {
        ControlCenterConfig.BudgetTargetAutoExecution autoConfig = configSnapshot.getBudgetTargetAutoExecution();
        return payloadOf(
                "controlCenterVersion", controlCenterSettingsProvider.getCurrentVersion(),
                "controlCenterUpdatedAt", controlCenterSettingsProvider.getUpdatedAt(),
                "startedBy", BudgetTargetAuditSupport.normalizeActor(actor),
                "traceId", session.getTraceId(),
                "sessionBudgetUsdt", session.getBudgetAmountUsdt(),
                "targetProfitUsdt", session.getTargetProfitUsdt(),
                "maxConcurrentPositions", session.getMaxConcurrentPositions(),
                "autoTargetMode", payloadOf(
                        "enabled", autoConfig.isEnabled(),
                        "armed", autoConfig.isArmed(),
                        "readOnly", autoConfig.isReadOnly(),
                        "defaultBudgetUsdt", autoConfig.getDefaultBudgetUsdt(),
                        "defaultTargetProfitUsdt", autoConfig.getDefaultTargetProfitUsdt(),
                        "maxConcurrentPositions", autoConfig.getMaxConcurrentPositions(),
                        "allowNewSessionStart", autoConfig.isAllowNewSessionStart(),
                        "allowCloseAllOnTarget", autoConfig.isAllowCloseAllOnTarget(),
                        "killSwitch", autoConfig.isKillSwitch(),
                        "requireBinanceHealthPass", autoConfig.isRequireBinanceHealthPass(),
                        "requireOperatorConfirmationForStop", autoConfig.isRequireOperatorConfirmationForStop(),
                        "sessionTimeoutMinutes", autoConfig.getSessionTimeoutMinutes()),
                "liveExecution", payloadOf(
                        "readOnly", configSnapshot.getLiveExecution().isReadOnly(),
                        "enabled", controlCenterSettingsProvider.can(LIVE_EXECUTION_PERMISSION)),
                "scan", payloadOf("safeMode", configSnapshot.getScan().isSafeMode()));
    }

    private BudgetTargetSessionStopReason resolveStopReason(BudgetTargetSessionCompletionReason reason) {
        if (reason == null) {
            return BudgetTargetSessionStopReason.UNKNOWN;
        }
        return switch (reason) {
            case TARGET_REACHED -> BudgetTargetSessionStopReason.TARGET_REACHED;
            case OPERATOR_STOPPED -> BudgetTargetSessionStopReason.OPERATOR_STOPPED;
            case MANUAL_OFF -> BudgetTargetSessionStopReason.MANUAL_OFF;
            case READ_ONLY_ENABLED -> BudgetTargetSessionStopReason.READ_ONLY_ENABLED;
            case BINANCE_HEALTH_FAILED -> BudgetTargetSessionStopReason.BINANCE_HEALTH_FAILED;
            case BUDGET_EXHAUSTED -> BudgetTargetSessionStopReason.BUDGET_EXHAUSTED;
            case FATAL_SYNC_ERROR -> BudgetTargetSessionStopReason.FATAL_SYNC_ERROR;
            case EXECUTION_FAILURE_THRESHOLD -> BudgetTargetSessionStopReason.EXECUTION_FAILURE_THRESHOLD;
            case RUNTIME_BLOCKED -> BudgetTargetSessionStopReason.RUNTIME_BLOCKED;
            case KILL_SWITCH -> BudgetTargetSessionStopReason.KILL_SWITCH;
            case SESSION_TIMEOUT -> BudgetTargetSessionStopReason.SESSION_TIMEOUT;
            case FLATTEN_FAILED -> BudgetTargetSessionStopReason.FLATTEN_FAILED;
            case UNKNOWN -> BudgetTargetSessionStopReason.UNKNOWN;
        };
    }

    private BudgetTargetSessionStatus resolveTerminalStatus(BudgetTargetSession session,
            BudgetTargetSessionCompletionReason reason) {
        if (reason == BudgetTargetSessionCompletionReason.FATAL_SYNC_ERROR
                || reason == BudgetTargetSessionCompletionReason.EXECUTION_FAILURE_THRESHOLD) {
            return BudgetTargetSessionStatus.FAILED;
        }
        if (session.getOpenedPositionsTotal() == 0 && session.getActivePositionsCount() == 0) {
            return BudgetTargetSessionStatus.CANCELLED;
        }
        return BudgetTargetSessionStatus.STOPPED;
    }

    private BudgetTargetSessionStopReason resolveTerminalStopReason(BudgetTargetSession session,
            BudgetTargetSessionCompletionReason reason) {
        if (session.getOpenedPositionsTotal() == 0 && session.getActivePositionsCount() == 0) {
            return BudgetTargetSessionStopReason.CANCELLED;
        }
        if (reason == null) {
            return BudgetTargetSessionStopReason.STOPPED;
        }
        BudgetTargetSessionStopReason stopReason = resolveStopReason(reason);
        return stopReason == BudgetTargetSessionStopReason.UNKNOWN ? BudgetTargetSessionStopReason.STOPPED : stopReason;
    }

    public Optional<StopDecision> evaluateRuntimeStop(ControlCenterConfig configSnapshot,
            BudgetTargetSession session,
            boolean includeHealthCheck) {
        if (session == null || session.getStatus().isTerminal()) {
            return Optional.empty();
        }
        ControlCenterConfig.BudgetTargetAutoExecution config = configSnapshot.getBudgetTargetAutoExecution();
        if (!config.isArmed()) {
            return Optional.of(new StopDecision(
                    BudgetTargetSessionCompletionReason.OPERATOR_STOPPED,
                    "Control Center disarmed the budget-target auto-execution session.",
                    payloadOf("configPath", "budgetTargetAutoExecution.armed")));
        }
        if (!config.isEnabled()) {
            return Optional.of(new StopDecision(
                    BudgetTargetSessionCompletionReason.FATAL_SYNC_ERROR,
                    "Control Center disabled the budget-target auto-execution runtime while the session was active.",
                    payloadOf("configPath", "budgetTargetAutoExecution.enabled")));
        }
        if (config.isKillSwitch()) {
            return Optional.of(new StopDecision(
                    BudgetTargetSessionCompletionReason.KILL_SWITCH,
                    "Control Center kill switch forced the budget-target auto-execution session to stop.",
                    payloadOf("configPath", "budgetTargetAutoExecution.killSwitch")));
        }
        if (session.getStatus() == BudgetTargetSessionStatus.ARMED && !config.isAllowNewSessionStart()) {
            return Optional.of(new StopDecision(
                    BudgetTargetSessionCompletionReason.OPERATOR_STOPPED,
                    "Control Center blocked new session starts while the session was still ARMED.",
                    payloadOf("configPath", "budgetTargetAutoExecution.allowNewSessionStart")));
        }
        if (session.getStatus() != BudgetTargetSessionStatus.ARMED) {
            if (config.isReadOnly()) {
                return Optional.of(new StopDecision(
                        BudgetTargetSessionCompletionReason.READ_ONLY_ENABLED,
                        "Budget-target auto-execution entered READ-ONLY mode while the session was active.",
                        payloadOf("configPath", "budgetTargetAutoExecution.readOnly")));
            }
            if (configSnapshot.getLiveExecution().isReadOnly()) {
                return Optional.of(new StopDecision(
                        BudgetTargetSessionCompletionReason.READ_ONLY_ENABLED,
                        "Live execution entered READ-ONLY mode while the session was active.",
                        payloadOf("configPath", "liveExecution.readOnly")));
            }
            if (!controlCenterSettingsProvider.can(LIVE_EXECUTION_PERMISSION)) {
                return Optional.of(new StopDecision(
                        BudgetTargetSessionCompletionReason.FATAL_SYNC_ERROR,
                        "Live execution capability was disabled while the session was active.",
                        payloadOf("permissionKey", LIVE_EXECUTION_PERMISSION)));
            }
            if (configSnapshot.getScan().isSafeMode()) {
                return Optional.of(new StopDecision(
                        BudgetTargetSessionCompletionReason.FATAL_SYNC_ERROR,
                        "scan.safeMode became active while the session was running.",
                        payloadOf("configPath", "scan.safeMode")));
            }
            if (includeHealthCheck && config.isRequireBinanceHealthPass()) {
                LiveTradingPreflightDTO health = liveTradingPreflightService.evaluateHealth("BTCUSDT", null);
                if (!health.isExecutable()) {
                    return Optional.of(new StopDecision(
                            BudgetTargetSessionCompletionReason.BINANCE_HEALTH_FAILED,
                            health.getSummary().getPrimaryBlockerMessage() != null
                                    ? health.getSummary().getPrimaryBlockerMessage()
                                    : "Binance health checks failed while the session was active.",
                            payloadOf(
                                    "summary", payloadOf(
                                            "primaryBlockerCode", health.getSummary().getPrimaryBlockerCode(),
                                            "primaryBlockerMessage", health.getSummary().getPrimaryBlockerMessage()))));
                }
            }
        }
        if (isSessionTimedOut(session, config)) {
            return Optional.of(new StopDecision(
                    BudgetTargetSessionCompletionReason.SESSION_TIMEOUT,
                    "Budget-target auto-execution session timed out and is stopping.",
                    payloadOf("configPath", "budgetTargetAutoExecution.sessionTimeoutMinutes")));
        }
        if (session.getStatus() == BudgetTargetSessionStatus.TARGET_REACHED) {
            return Optional.of(new StopDecision(
                    BudgetTargetSessionCompletionReason.TARGET_REACHED,
                    "Target-reached session is continuing into graceful STOPPING.",
                    payloadOf("targetProfitUsdt", session.getTargetProfitUsdt())));
        }
        return Optional.empty();
    }

    private StartGateDecision blocked(String code, String message, Map<String, Object> payload) {
        return new StartGateDecision(false, code, message, payload);
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("Unable to serialize budget-target session payload: {}", ex.getMessage());
            return null;
        }
    }

    private Map<String, Object> payloadOf(Object... entries) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (entries == null) {
            return payload;
        }
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("Payload entries must be key/value pairs.");
        }
        for (int index = 0; index < entries.length; index += 2) {
            Object key = entries[index];
            if (key instanceof String stringKey) {
                payload.put(stringKey, entries[index + 1]);
            }
        }
        return payload;
    }

    private record StartGateDecision(boolean ready, String code, String message, Map<String, Object> payload) {
    }

    public record StopDecision(
            BudgetTargetSessionCompletionReason reason,
            String message,
            Map<String, Object> payload) {
    }
}
