package com.tradebot.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BudgetTargetEventTimelineResponseDTO {
    private List<BudgetTargetEventTimelineItemDTO> items = new ArrayList<>();
    private int total;
    private int filteredCount;
}
