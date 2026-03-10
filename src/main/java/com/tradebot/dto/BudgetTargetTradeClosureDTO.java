package com.tradebot.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
public class BudgetTargetTradeClosureDTO {
    private UUID id;
    private String closeReason;
    private BigDecimal closedQty;
    private BigDecimal closedPrice;
    private String closingClientOrderId;
    private Long closingOrderId;
    private Map<String, Object> finalPositionSnapshot = Map.of();
    private Map<String, Object> closeResponse = Map.of();
    private Instant closedAt;
}
