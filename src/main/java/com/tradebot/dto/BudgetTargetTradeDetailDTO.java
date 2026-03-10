package com.tradebot.dto;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class BudgetTargetTradeDetailDTO {
    private BudgetTargetTradeHistoryItemDTO trade;
    private LiveTradeExecutionDTO execution;
    private List<BudgetTargetEventTimelineItemDTO> decisionAudits = new ArrayList<>();
    private List<BudgetTargetTradeOrderDTO> orders = new ArrayList<>();
    private BudgetTargetTradeClosureDTO closure;
    private List<BudgetTargetPnlLedgerEntryDTO> pnlLedgerEntries = new ArrayList<>();
    private List<ExchangeSyncSnapshotDTO> syncSnapshots = new ArrayList<>();
    private Instant generatedAt;
}
