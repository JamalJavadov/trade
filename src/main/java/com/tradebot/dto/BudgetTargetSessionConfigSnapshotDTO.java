package com.tradebot.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class BudgetTargetSessionConfigSnapshotDTO {
    private Integer controlCenterVersion;
    private Instant controlCenterUpdatedAt;
    private String startedBy;
    private String traceId;
    private BigDecimal sessionBudgetUsdt;
    private BigDecimal targetProfitUsdt;
    private Integer maxConcurrentPositions;
    private AutoTargetMode autoTargetMode = new AutoTargetMode();
    private LiveExecution liveExecution = new LiveExecution();
    private Scan scan = new Scan();

    @Data
    public static class AutoTargetMode {
        private boolean enabled;
        private boolean armed;
        private boolean readOnly;
        private BigDecimal defaultBudgetUsdt;
        private BigDecimal defaultTargetProfitUsdt;
        private Integer maxConcurrentPositions;
        private boolean allowNewSessionStart;
        private boolean allowCloseAllOnTarget;
        private boolean killSwitch;
        private boolean requireBinanceHealthPass;
        private boolean requireOperatorConfirmationForStop;
        private Integer sessionTimeoutMinutes;
    }

    @Data
    public static class LiveExecution {
        private boolean readOnly;
        private boolean enabled;
    }

    @Data
    public static class Scan {
        private boolean safeMode;
    }
}
