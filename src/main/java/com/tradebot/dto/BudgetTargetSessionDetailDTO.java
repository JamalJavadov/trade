package com.tradebot.dto;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class BudgetTargetSessionDetailDTO {
    private BudgetTargetSessionSummaryDTO summary;
    private BudgetTargetSessionConfigSnapshotDTO configSnapshot;
    private UUID pendingScanRunId;
    private String traceId;
    private BudgetTargetCriticalErrorDTO latestCriticalError;
    private BudgetTargetSyncHealthDTO syncHealth;
    private Instant lastEventAt;
    private int timelineEventCount;
    private int tradeCount;
}
