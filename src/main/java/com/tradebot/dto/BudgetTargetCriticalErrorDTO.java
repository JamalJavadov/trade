package com.tradebot.dto;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class BudgetTargetCriticalErrorDTO {
    private String sourceType;
    private String eventCategory;
    private String eventType;
    private String code;
    private String message;
    private Instant eventTs;
    private UUID executionId;
}
