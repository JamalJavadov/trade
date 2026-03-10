package com.tradebot.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
public class BudgetTargetPnlLedgerEntryDTO {
    private UUID id;
    private String eventType;
    private BigDecimal amountUsdt;
    private Instant eventTs;
    private String sourceType;
    private String sourceRef;
    private String notes;
    private Map<String, Object> before = Map.of();
    private Map<String, Object> after = Map.of();
}
