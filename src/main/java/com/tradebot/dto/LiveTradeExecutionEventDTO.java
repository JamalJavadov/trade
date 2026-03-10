package com.tradebot.dto;

import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
public class LiveTradeExecutionEventDTO {
    private UUID id;
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
    private String errorCode;
    private Map<String, Object> payload;
    private Instant createdAt;
}
