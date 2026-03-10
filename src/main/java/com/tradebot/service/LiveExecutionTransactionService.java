package com.tradebot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.entity.BudgetTargetSession;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionEvent;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.entity.Recommendation;
import com.tradebot.repository.BudgetTargetSessionRepository;
import com.tradebot.repository.LiveTradeExecutionEventRepository;
import com.tradebot.repository.LiveTradeExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.OptionalInt;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LiveExecutionTransactionService {

    public static final List<LiveTradeExecutionState> ACTIVE_STATES_FOR_SLOTS = List.of(
            LiveTradeExecutionState.CREATED,
            LiveTradeExecutionState.PREFLIGHT_VALIDATING,
            LiveTradeExecutionState.ENTRY_SUBMITTING,
            LiveTradeExecutionState.ENTRY_SUBMITTED,
            LiveTradeExecutionState.ENTRY_FILLED,
            LiveTradeExecutionState.PROTECTION_SUBMITTING,
            LiveTradeExecutionState.PROTECTION_ACTIVE,
            LiveTradeExecutionState.ACTIVE,
            LiveTradeExecutionState.CLOSING,
            LiveTradeExecutionState.RECONCILING);

    private final BudgetTargetSessionRepository budgetTargetSessionRepository;
    private final LiveTradeExecutionRepository liveTradeExecutionRepository;
    private final LiveTradeExecutionEventRepository liveTradeExecutionEventRepository;
    private final LiveTradePersistenceService liveTradePersistenceService;
    private final BudgetTargetSessionStreamPublisher sessionStreamPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    public PreparedExecution prepareExecution(ApprovedExecutionCommand command,
            Recommendation recommendation,
            String payloadSnapshotJson) {
        UUID recommendationId = recommendation.getId();
        UUID idempotencyKey = command.idempotencyKey();

        LiveTradeExecution existingAttempt = liveTradeExecutionRepository
                .findFirstByRecommendation_IdAndClientRequestId(recommendationId, idempotencyKey)
                .orElse(null);
        if (existingAttempt != null) {
            return PreparedExecution.duplicate(existingAttempt);
        }

        BudgetTargetSession budgetTargetSession = null;
        if (command.sessionId() != null) {
            budgetTargetSession = budgetTargetSessionRepository.findById(command.sessionId())
                    .orElseThrow(() -> new NoSuchElementException(
                            "Budget target session not found: " + command.sessionId()));
            if (!budgetTargetSession.getStatus().allowsTradeOpens()) {
                throw new LiveExecutionPreparationException(
                        LiveTradingBlockerCodes.AUTO_SESSION_NOT_OPEN_FOR_TRADES,
                        "Budget target session does not allow new trades: " + budgetTargetSession.getStatus(),
                        payloadOf(
                                "sessionId", budgetTargetSession.getId(),
                                "status", budgetTargetSession.getStatus().name()));
            }
        }

        LiveTradeExecution execution = new LiveTradeExecution();
        execution.setRecommendation(recommendation);
        execution.setBudgetTargetSession(budgetTargetSession);
        execution.setTriggerMode(command.triggerMode());
        execution.setSymbol(recommendation.getSymbol());
        execution.setSide(recommendation.getSide());
        execution.setOperatorId(normalizeOperatorId(command.operatorId()));
        execution.setTraceId(command.traceId());
        execution.setClientRequestId(idempotencyKey);
        execution.setDryRun(false);
        execution.setExecutionState(LiveTradeExecutionState.CREATED);
        execution.setCreatedAt(Instant.now());
        execution.setUpdatedAt(Instant.now());
        execution.setPayloadSnapshotJson(payloadSnapshotJson);

        RequestedExecutionSaveResult saveResult = saveRequestedExecution(recommendationId, idempotencyKey, execution);
        if (!saveResult.created()) {
            return PreparedExecution.duplicate(saveResult.execution());
        }

        execution = saveResult.execution();
        appendEvent(execution, "CREATED", execution.getExecutionState().name(),
                command.requestMessage(),
                null,
                payloadOf(
                        "traceId", command.traceId(),
                        "clientRequestId", idempotencyKey,
                        "triggerMode", command.triggerMode().name(),
                        "budgetTargetSessionId", command.sessionId()));

        execution.setExecutionState(LiveTradeExecutionState.PREFLIGHT_VALIDATING);
        execution.setUpdatedAt(Instant.now());
        execution = liveTradeExecutionRepository.save(execution);
        appendEvent(execution, "PREFLIGHT_VALIDATING", execution.getExecutionState().name(),
                "Running final exchange preflight before submission.",
                null,
                payloadOf("traceId", command.traceId()));
        return PreparedExecution.created(execution);
    }

    @Transactional
    public LiveTradeExecution reserveEntrySubmission(UUID executionId,
            ApprovedExecutionCommand command,
            BigDecimal reservedMarginUsdt,
            BigDecimal requestedQty,
            String entryClientOrderId,
            String slClientOrderId,
            String tpClientOrderId,
            String emergencyCloseClientOrderId) {
        LiveTradeExecution execution = liveTradeExecutionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new NoSuchElementException("Live execution not found: " + executionId));

        BudgetTargetSession budgetTargetSession = null;
        if (execution.getSession() != null) {
            UUID sessionId = execution.getSession().getId();
            budgetTargetSession = budgetTargetSessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new NoSuchElementException("Budget target session not found: " + sessionId));
            if (!budgetTargetSession.getStatus().allowsTradeOpens()) {
                throw new LiveExecutionPreparationException(
                        LiveTradingBlockerCodes.AUTO_SESSION_NOT_OPEN_FOR_TRADES,
                        "Budget target session does not allow new trades: " + budgetTargetSession.getStatus(),
                        payloadOf(
                                "sessionId", budgetTargetSession.getId(),
                                "status", budgetTargetSession.getStatus().name()));
            }
            execution.setBudgetTargetSession(budgetTargetSession);
            execution.setPositionSlot(allocatePositionSlot(budgetTargetSession));
        }

        execution.setReservedMarginUsdt(reservedMarginUsdt);
        execution.setRequestedQty(requestedQty);
        execution.setRequestedBudgetSliceUsdt(command.allocatedBudgetSliceUsdt() != null
                ? command.allocatedBudgetSliceUsdt()
                : reservedMarginUsdt);
        execution.setEntryClientOrderId(entryClientOrderId);
        execution.setSlClientOrderId(slClientOrderId);
        execution.setTpClientOrderId(tpClientOrderId);
        execution.setEmergencyCloseClientOrderId(emergencyCloseClientOrderId);
        execution.setExecutionState(LiveTradeExecutionState.ENTRY_SUBMITTING);
        execution.setSubmittedAt(Instant.now());
        execution.setUpdatedAt(Instant.now());
        execution.setErrorCode(null);
        execution.setErrorMessage(null);
        execution.setErrorDetailsJson(null);
        execution.setCriticalIssueJson(null);
        execution.setRequiresIntervention(false);
        execution = liveTradeExecutionRepository.save(execution);
        refreshSessionRollup(execution);
        appendEvent(execution, "ENTRY_SUBMITTING", execution.getExecutionState().name(),
                "Submitting live Binance Futures entry order.",
                null,
                payloadOf("traceId", command.traceId()));
        return execution;
    }

    private RequestedExecutionSaveResult saveRequestedExecution(UUID recommendationId,
            UUID clientRequestId,
            LiveTradeExecution execution) {
        try {
            return new RequestedExecutionSaveResult(liveTradeExecutionRepository.save(execution), true);
        } catch (DataIntegrityViolationException ex) {
            LiveTradeExecution existing = liveTradeExecutionRepository
                    .findFirstByRecommendation_IdAndClientRequestId(recommendationId, clientRequestId)
                    .orElseThrow(() -> ex);
            return new RequestedExecutionSaveResult(existing, false);
        }
    }

    private Integer allocatePositionSlot(BudgetTargetSession session) {
        int positionLimit = Math.min(
                ControlCenterConfig.BudgetTargetAutoExecution.LOCKED_MAX_CONCURRENT_POSITIONS,
                Math.max(1, session.getMaxConcurrentPositions()));
        List<LiveTradeExecution> activeExecutions = liveTradeExecutionRepository
                .findBySession_IdAndPositionSlotIsNotNullAndExecutionStatusInOrderByPositionSlotAsc(
                        session.getId(),
                        ACTIVE_STATES_FOR_SLOTS);
        OptionalInt nextSlot = java.util.stream.IntStream.rangeClosed(1, positionLimit)
                .filter(candidate -> activeExecutions.stream()
                        .noneMatch(execution -> execution.getPositionSlot() != null && execution.getPositionSlot() == candidate))
                .findFirst();
        if (nextSlot.isEmpty()) {
            throw new LiveExecutionPreparationException(
                    "ACTIVE_LIMIT_REACHED",
                    "Active position limit reached for session "
                            + session.getId()
                            + " with locked maxConcurrentPositions="
                            + positionLimit,
                    payloadOf(
                            "sessionId", session.getId(),
                            "positionLimit", positionLimit));
        }
        return nextSlot.getAsInt();
    }

    private void refreshSessionRollup(LiveTradeExecution execution) {
        if (execution.getSession() == null) {
            return;
        }
        liveTradePersistenceService.refreshSessionRollup(execution.getSession(), ACTIVE_STATES_FOR_SLOTS);
    }

    private void appendEvent(LiveTradeExecution execution,
            String eventType,
            String eventStatus,
            String message,
            String errorCode,
            Object payload) {
        Map<String, Object> snapshot = BudgetTargetAuditSupport.executionSnapshot(execution);
        LiveTradeExecutionEvent event = new LiveTradeExecutionEvent();
        event.setExecution(execution);
        event.setEventType(eventType);
        event.setEventStatus(eventStatus);
        event.setNotes(message);
        event.setErrorCode(errorCode);
        event.setTraceId(execution.getTraceId());
        event.setEventCategory(BudgetTargetAuditSupport.categoryForExecutionEvent(eventType));
        event.setSeverity(BudgetTargetAuditSupport.severityForExecutionEvent(eventType));
        event.setActor(BudgetTargetAuditSupport.normalizeActor(execution.getOperatorId()));
        event.setBeforeJson(writeJson(snapshot));
        event.setAfterJson(writeJson(BudgetTargetAuditSupport.executionEventState(
                execution,
                execution.getOperatorId(),
                errorCode,
                payloadOf(
                        "executionStatus", execution.getExecutionState().name(),
                        "errorCode", errorCode,
                        "payload", payload,
                        "positionSlot", execution.getPositionSlot(),
                        "sessionId", execution.getSession() != null ? execution.getSession().getId() : null,
                        "requiresIntervention", execution.isRequiresIntervention(),
                        "criticalIssue", readJson(execution.getCriticalIssueJson())))));
        event.setEventTs(Instant.now());
        LiveTradeExecutionEvent saved = liveTradeExecutionEventRepository.save(event);
        sessionStreamPublisher.publish(saved);
    }

    private String normalizeOperatorId(String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            return "system";
        }
        return operatorId.trim();
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Object> payloadOf(Object... entries) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (entries == null) {
            return payload;
        }
        for (int index = 0; index + 1 < entries.length; index += 2) {
            Object key = entries[index];
            if (key instanceof String stringKey) {
                payload.put(stringKey, entries[index + 1]);
            }
        }
        return payload;
    }

    public record PreparedExecution(LiveTradeExecution execution, boolean created) {
        private static PreparedExecution created(LiveTradeExecution execution) {
            return new PreparedExecution(execution, true);
        }

        private static PreparedExecution duplicate(LiveTradeExecution execution) {
            return new PreparedExecution(execution, false);
        }
    }

    public static final class LiveExecutionPreparationException extends RuntimeException {
        private final String code;
        private final Map<String, Object> details;

        public LiveExecutionPreparationException(String code, String message, Map<String, Object> details) {
            super(message);
            this.code = code;
            this.details = details == null ? Map.of() : details;
        }

        public String code() {
            return code;
        }

        public Map<String, Object> details() {
            return details;
        }
    }

    private record RequestedExecutionSaveResult(LiveTradeExecution execution, boolean created) {
    }
}
