package com.tradebot.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
public class BudgetTargetSessionSummaryDTO {
    private UUID id;
    private String status;
    private Instant startedAt;
    private Instant endedAt;
    private String startedBy;
    private String startReason;
    private Instant targetSatisfiedAt;
    private BigDecimal budgetAmountUsdt;
    private BigDecimal targetProfitUsdt;
    private BigDecimal realizedNetPnlUsdt;
    private BigDecimal totalGrossPnlUsdt;
    private BigDecimal feeTotalUsdt;
    private int winCount;
    private int lossCount;
    private int activeTradeCount;
    private int completedTradeCount;
    private String stopReason;
    private BudgetTargetCriticalErrorDTO mostRecentCriticalError;
    private BudgetTargetSyncHealthDTO syncHealth;
}
