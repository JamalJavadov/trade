package com.tradebot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.dto.LiveTradeExecutionDTO;
import com.tradebot.dto.LiveTradeExecutionEventDTO;
import com.tradebot.dto.LiveTradeExecutionReferenceDTO;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LiveTradingMapper {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public LiveTradeExecutionReferenceDTO toReference(LiveTradeExecution execution) {
        if (execution == null) {
            return null;
        }
        LiveTradeExecutionReferenceDTO dto = new LiveTradeExecutionReferenceDTO();
        dto.setId(execution.getId());
        dto.setExecutionState(execution.getExecutionStatus().name());
        dto.setErrorCode(execution.getErrorCode());
        dto.setErrorMessage(execution.getErrorMessage());
        dto.setCreatedAt(execution.getCreatedAt());
        dto.setUpdatedAt(execution.getUpdatedAt());
        return dto;
    }

    public LiveTradeExecutionDTO toDetail(LiveTradeExecution execution, List<LiveTradeExecutionEvent> events) {
        LiveTradeExecutionDTO dto = new LiveTradeExecutionDTO();
        dto.setId(execution.getId());
        dto.setRecommendationId(execution.getRecommendation().getId());
        dto.setSessionId(execution.getSession() != null ? execution.getSession().getId() : null);
        dto.setBudgetTargetSessionId(dto.getSessionId());
        dto.setSymbol(execution.getSymbol());
        dto.setSide(execution.getSide());
        dto.setTriggerMode(execution.getTriggerMode().name());
        dto.setOperatorId(execution.getOperatorId());
        dto.setTraceId(execution.getTraceId());
        dto.setDryRun(execution.isDryRun());
        dto.setExecutionStatus(execution.getExecutionStatus().name());
        dto.setExecutionState(execution.getExecutionStatus().name());
        dto.setErrorCode(execution.getErrorCode());
        dto.setErrorMessage(execution.getErrorMessage());
        dto.setErrorDetails(readJson(execution.getErrorDetailsJson()));
        dto.setRequiresIntervention(execution.isRequiresIntervention());
        dto.setCriticalIssue(readCriticalIssue(execution.getCriticalIssueJson()));
        dto.setReservedMarginUsdt(execution.getReservedMarginUsdt());
        dto.setRequestedBudgetSliceUsdt(execution.getRequestedBudgetSliceUsdt());
        dto.setRequestedQty(execution.getRequestedQty());
        dto.setActualFilledQty(execution.getActualFilledQty());
        dto.setRealizedGrossPnlUsdt(execution.getRealizedGrossPnlUsdt());
        dto.setRealizedFeesUsdt(execution.getRealizedFeesUsdt());
        dto.setRealizedNetPnlUsdt(execution.getRealizedNetPnlUsdt());
        dto.setCloseReason(execution.getCloseReason());
        dto.setPositionSlot(execution.getPositionSlot());
        dto.setCreatedAt(execution.getCreatedAt());
        dto.setUpdatedAt(execution.getUpdatedAt());
        dto.setSubmittedAt(execution.getSubmittedAt());
        dto.setCompletedAt(execution.getCompletedAt());
        dto.setLastReconciledAt(execution.getLastReconciledAt());
        dto.setReconcileCount(execution.getReconcileCount());
        dto.getOrderRefs().setEntryClientOrderId(execution.getEntryClientOrderId());
        dto.getOrderRefs().setSlClientOrderId(execution.getSlClientOrderId());
        dto.getOrderRefs().setTpClientOrderId(execution.getTpClientOrderId());
        dto.getOrderRefs().setEmergencyCloseClientOrderId(execution.getEmergencyCloseClientOrderId());
        dto.getOrderRefs().setEntryOrderId(execution.getEntryOrderId());
        dto.getOrderRefs().setSlOrderId(execution.getSlOrderId());
        dto.getOrderRefs().setTpOrderId(execution.getTpOrderId());
        dto.getOrderRefs().setEmergencyCloseOrderId(execution.getEmergencyCloseOrderId());
        dto.setPayloadSnapshot(readJson(execution.getPayloadSnapshotJson()));
        dto.setPreflight(readJson(execution.getPreflightJson()));
        dto.setEntryResponse(readJson(execution.getEntryResponseJson()));
        dto.setProtectionResponse(readJson(execution.getProtectionResponseJson()));
        dto.setExchangeResponse(readJson(execution.getExchangeResponseJson()));
        if (events != null) {
            dto.setEvents(events.stream().map(this::toEvent).toList());
        }
        return dto;
    }

    public Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            return Map.of("raw", json);
        }
    }

    private LiveTradeExecutionEventDTO toEvent(LiveTradeExecutionEvent event) {
        LiveTradeExecutionEventDTO dto = new LiveTradeExecutionEventDTO();
        dto.setId(event.getId());
        dto.setEventCategory(event.getEventCategory() != null ? event.getEventCategory().name() : null);
        dto.setSeverity(event.getSeverity() != null ? event.getSeverity().name() : null);
        dto.setActor(event.getActor());
        dto.setEventType(event.getEventType());
        dto.setEventStatus(event.getEventStatus());
        dto.setBefore(readJson(event.getBeforeJson()));
        dto.setAfter(readJson(event.getAfterJson()));
        dto.setNotes(event.getNotes());
        dto.setTraceId(event.getTraceId());
        dto.setEventTs(event.getEventTs());
        dto.setMessage(event.getNotes());
        dto.setErrorCode(event.getErrorCode());
        dto.setPayload(readJson(event.getAfterJson()));
        dto.setCreatedAt(event.getEventTs());
        return dto;
    }

    private LiveTradeExecutionDTO.CriticalIssue readCriticalIssue(String json) {
        Map<String, Object> payload = readJson(json);
        if (payload.isEmpty()) {
            return null;
        }
        LiveTradeExecutionDTO.CriticalIssue issue = new LiveTradeExecutionDTO.CriticalIssue();
        Object code = payload.get("code");
        Object message = payload.get("message");
        Object raisedAt = payload.get("raisedAt");
        Object details = payload.get("details");
        issue.setCode(code instanceof String text ? text : null);
        issue.setMessage(message instanceof String text ? text : null);
        issue.setDetails(details instanceof Map<?, ?> detailMap
                ? detailMap.entrySet().stream()
                        .filter(entry -> entry.getKey() instanceof String)
                        .filter(entry -> entry.getValue() != null)
                        .collect(java.util.stream.Collectors.toMap(
                                entry -> (String) entry.getKey(),
                                Map.Entry::getValue,
                                (left, right) -> right,
                                java.util.LinkedHashMap::new))
                : Map.of());
        if (raisedAt instanceof String text) {
            try {
                issue.setRaisedAt(java.time.Instant.parse(text));
            } catch (Exception ignored) {
                issue.setRaisedAt(null);
            }
        }
        return issue;
    }
}
