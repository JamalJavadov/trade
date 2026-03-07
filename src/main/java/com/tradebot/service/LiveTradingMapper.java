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
        dto.setExecutionState(execution.getExecutionState().name());
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
        dto.setSymbol(execution.getSymbol());
        dto.setSide(execution.getSide());
        dto.setTriggerMode(execution.getTriggerMode().name());
        dto.setOperatorId(execution.getOperatorId());
        dto.setTraceId(execution.getTraceId());
        dto.setDryRun(execution.isDryRun());
        dto.setExecutionState(execution.getExecutionState().name());
        dto.setErrorCode(execution.getErrorCode());
        dto.setErrorMessage(execution.getErrorMessage());
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
        dto.setEventType(event.getEventType());
        dto.setEventStatus(event.getEventStatus());
        dto.setMessage(event.getMessage());
        dto.setErrorCode(event.getErrorCode());
        dto.setPayload(readJson(event.getPayloadJson()));
        dto.setCreatedAt(event.getCreatedAt());
        return dto;
    }
}
