package com.tradebot.demo.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class DemoStatusResponseDTO {
    private boolean enabled;
    private boolean running;
    private int intervalMinutes;
    private int maxOpenPositions;
    private AccountDTO account;
    private long openPositionsCount;
    private long closedTradesCount;
    private String lastDemoRunStatus;
    private long cycleCountTotal;
    private long cycleCountFinished;
    private long cycleCountFailed;
    private boolean cycleRunning;
    private String workflowPhase;
    private DemoTradeSummaryDTO lastDemoTradeSummary;
    private DemoTradeSummaryDTO lastOpenTrade;
    private DemoTradeSummaryDTO lastClosedTrade;
    private BigDecimal winRate;

    @Data
    public static class AccountDTO {
        private BigDecimal balanceUsdt;
        private BigDecimal equityUsdt;
    }
}
