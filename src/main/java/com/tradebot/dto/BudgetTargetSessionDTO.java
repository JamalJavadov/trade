package com.tradebot.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
public class BudgetTargetSessionDTO {
    private UUID id;
    private String stopReason;
    private String stopReasonMessage;
    private BigDecimal budgetAmountUsdt;
    private BigDecimal targetProfitUsdt;
    private String status;
    private String completionReason;
    private BigDecimal sessionBudgetUsdt;
    private BigDecimal finalTargetNetProfitUsdt;
    private BigDecimal realizedNetPnlUsdt;
    private BigDecimal unrealizedNetPnlUsdt;
    private BigDecimal remainingBankrollUsdt;
    private int maxConcurrentPositions;
    private int activePositionsCount;
    private int openedPositionsTotal;
    private int closedPositionsTotal;
    private int activeTradeLimit;
    private long activeTradeCount;
    private long openedTradeCount;
    private UUID pendingScanRunId;
    private boolean stopRequested;
    private String lastErrorCode;
    private String lastErrorMessage;
    private String failureReasonCode;
    private String failureReasonMessage;
    private int executionFailureCount;
    private Instant startedAt;
    private Instant endedAt;
    private Instant completedAt;
    private Instant updatedAt;
    private BudgetTargetSyncHealthDTO syncHealth;
}
