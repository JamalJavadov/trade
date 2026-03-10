package com.tradebot.dto;

import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
public class BudgetTargetSessionEventDTO {
    private UUID id;
    private UUID executionId;
    private String eventCategory;
    private String severity;
    private String actor;
    private String eventType;
    private String eventStatus;
    private Map<String, Object> before;
    private Map<String, Object> after;
    private String notes;
    private String traceId;
    private Instant eventTs;
    private String message;
    private String reasonCode;
    private Map<String, Object> payload;
    private Instant createdAt;
}
