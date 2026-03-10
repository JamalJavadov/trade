package com.tradebot.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
public class BudgetTargetTradeHistoryItemDTO {
    private UUID executionId;
    private UUID recommendationId;
    private UUID scanRunId;
    private String symbol;
    private String side;
    private String triggerMode;
    private BigDecimal allocatedBudgetSliceUsdt;
    private BigDecimal reservedMarginUsdt;
    private Integer positionSlot;
    private Instant openedAt;
    private Instant closedAt;
    private String executionState;
    private String openReason;
    private String closeReason;
    private BigDecimal realizedGrossPnlUsdt;
    private BigDecimal realizedFeesUsdt;
    private BigDecimal realizedNetPnlUsdt;
    private String outcome;
    private BudgetTargetCriticalErrorDTO latestCriticalError;
}
