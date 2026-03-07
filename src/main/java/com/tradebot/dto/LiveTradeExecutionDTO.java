package com.tradebot.dto;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
public class LiveTradeExecutionDTO {
    private UUID id;
    private UUID recommendationId;
    private String symbol;
    private String side;
    private String triggerMode;
    private String operatorId;
    private String traceId;
    private boolean dryRun;
    private String executionState;
    private String errorCode;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant submittedAt;
    private Instant completedAt;
    private Instant lastReconciledAt;
    private int reconcileCount;
    private OrderRefs orderRefs = new OrderRefs();
    private Map<String, Object> payloadSnapshot;
    private Map<String, Object> preflight;
    private Map<String, Object> exchangeResponse;
    private List<LiveTradeExecutionEventDTO> events = new ArrayList<>();

    @Data
    public static class OrderRefs {
        private String entryClientOrderId;
        private String slClientOrderId;
        private String tpClientOrderId;
        private String emergencyCloseClientOrderId;
        private Long entryOrderId;
        private Long slOrderId;
        private Long tpOrderId;
        private Long emergencyCloseOrderId;
    }
}
