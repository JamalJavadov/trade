package com.tradebot.dto;

import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
public class BudgetTargetEventTimelineItemDTO {
    private String id;
    private String sourceType;
    private String eventCategory;
    private String severity;
    private Instant eventTs;
    private UUID sessionId;
    private UUID executionId;
    private UUID recommendationId;
    private UUID scanRunId;
    private String symbol;
    private String eventType;
    private String status;
    private String reasonCode;
    private String actor;
    private String message;
    private Map<String, Object> summaryPayload = Map.of();
    private boolean debugAvailable;
}
