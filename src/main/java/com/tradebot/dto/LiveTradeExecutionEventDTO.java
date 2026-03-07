package com.tradebot.dto;

import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
public class LiveTradeExecutionEventDTO {
    private UUID id;
    private String eventType;
    private String eventStatus;
    private String message;
    private String errorCode;
    private Map<String, Object> payload;
    private Instant createdAt;
}
