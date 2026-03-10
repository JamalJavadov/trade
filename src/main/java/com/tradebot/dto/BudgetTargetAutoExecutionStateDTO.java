package com.tradebot.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class BudgetTargetAutoExecutionStateDTO {
    private Config config = new Config();
    private BudgetTargetSessionDTO activeSession;
    private BudgetTargetSessionDTO latestSession;
    private String primaryBlockedReasonCode;
    private String primaryBlockedReasonMessage;
    private String primaryBlockedReasonSource;
    private BudgetTargetSyncHealthDTO syncHealth;
    private List<LiveTradeExecutionDTO> orders = new ArrayList<>();
    private List<BudgetTargetSessionEventDTO> events = new ArrayList<>();
    private Instant serverTime;

    @Data
    public static class Config {
        private boolean enabled;
        private boolean armed;
        private boolean readOnly;
        private int maxConcurrentPositions;
        private BigDecimal defaultBudgetUsdt;
        private BigDecimal defaultTargetProfitUsdt;
        private boolean allowNewSessionStart;
        private boolean allowCloseAllOnTarget;
        private boolean killSwitch;
        private boolean requireBinanceHealthPass;
        private boolean requireOperatorConfirmationForStop;
        private int sessionTimeoutMinutes;
    }
}
