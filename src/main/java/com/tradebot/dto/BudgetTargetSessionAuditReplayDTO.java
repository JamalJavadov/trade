package com.tradebot.dto;

import lombok.Data;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class BudgetTargetSessionAuditReplayDTO {
    private BudgetTargetSessionDetailDTO session;
    private List<BudgetTargetEventTimelineItemDTO> timeline;
    private Map<String, BudgetTargetTradeDetailDTO> trades = new LinkedHashMap<>();
    private Instant generatedAt;
}
