package com.tradebot.dto;

import lombok.Data;

@Data
public class BudgetTargetSessionStreamEventDTO {
    private long eventId;
    private String sessionId;
    private BudgetTargetSessionSummaryDTO summary;
    private BudgetTargetEventTimelineItemDTO timelineItem;
}
