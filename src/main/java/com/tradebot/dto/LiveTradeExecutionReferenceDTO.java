package com.tradebot.dto;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class LiveTradeExecutionReferenceDTO {
    private UUID id;
    private String executionState;
    private String errorCode;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
}
