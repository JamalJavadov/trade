package com.tradebot.dto;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class BudgetTargetSyncHealthDTO {
    private String status;
    private Instant lastSyncAt;
    private Instant lastSuccessfulSyncAt;
    private String latestSyncType;
    private String latestErrorCode;
    private String latestErrorMessage;
    private boolean divergenceDetected;
    private boolean requiresIntervention;
    private boolean gateNewTrades;
    private String gateReasonCode;
    private String gateReasonMessage;
    private int openPositionCount;
    private int activeOpenOrderCount;
    private int activeProtectionOrderCount;
    private boolean closeAllInProgress;
    private List<String> affectedExecutionIds = new ArrayList<>();
}
