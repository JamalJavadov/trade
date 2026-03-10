package com.tradebot.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
public class BudgetTargetTradeOrderDTO {
    private UUID id;
    private String orderRole;
    private String clientOrderId;
    private Long exchangeOrderId;
    private String clientAlgoId;
    private Long exchangeAlgoId;
    private BigDecimal requestedQty;
    private BigDecimal executedQty;
    private BigDecimal limitPrice;
    private BigDecimal triggerPrice;
    private BigDecimal avgFillPrice;
    private String orderStatus;
    private Map<String, Object> requestPayload = Map.of();
    private Map<String, Object> responsePayload = Map.of();
    private Map<String, Object> snapshotPayload = Map.of();
    private Instant createdAt;
    private Instant updatedAt;
}
