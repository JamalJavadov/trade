package com.tradebot.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BudgetTargetTradeHistoryResponseDTO {
    private List<BudgetTargetTradeHistoryItemDTO> items = new ArrayList<>();
    private int total;
    private int activeCount;
    private int completedCount;
}
