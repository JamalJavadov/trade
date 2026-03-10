package com.tradebot.controlcenter;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ControlCenterConfig {

    private Map<String, Boolean> permissions = new LinkedHashMap<>();
    private Scan scan = new Scan();
    private Risk risk = new Risk();
    private Alerts alerts = new Alerts();
    private Ai ai = new Ai();
    private DemoTrading demoTrading = new DemoTrading();
    private LiveExecution liveExecution = new LiveExecution();
    private BudgetTargetAutoExecution budgetTargetAutoExecution = new BudgetTargetAutoExecution();
    private StrategyLocks strategyLocks = new StrategyLocks();

    public void ensureDefaults() {
        if (permissions == null) {
            permissions = new LinkedHashMap<>();
        }
        if (scan == null) {
            scan = new Scan();
        }
        if (risk == null) {
            risk = new Risk();
        }
        if (alerts == null) {
            alerts = new Alerts();
        }
        if (ai == null) {
            ai = new Ai();
        }
        if (demoTrading == null) {
            demoTrading = new DemoTrading();
        }
        if (liveExecution == null) {
            liveExecution = new LiveExecution();
        }
        if (budgetTargetAutoExecution == null) {
            budgetTargetAutoExecution = new BudgetTargetAutoExecution();
        }
        if (strategyLocks == null) {
            strategyLocks = new StrategyLocks();
        }
        risk.ensureDefaults();
        alerts.ensureDefaults();
        ai.ensureDefaults();
        demoTrading.ensureDefaults();
        liveExecution.ensureDefaults();
        budgetTargetAutoExecution.ensureDefaults();
        strategyLocks.ensureDefaults();
    }

    @Data
    public static class Scan {
        private boolean autoscanEnabled = false;
        private int intervalMinutes = 20;
        private boolean safeMode = false;
    }

    @Data
    public static class Risk {
        private BigDecimal budgetUsdt = new BigDecimal("50");
        private BigDecimal maxBudgetPct = new BigDecimal("5.0");
        private BigDecimal equityOverrideUsdt;
        private BigDecimal maxEquityPctLocked = BigDecimal.ONE;

        public void ensureDefaults() {
            if (budgetUsdt == null) {
                budgetUsdt = new BigDecimal("50");
            }
            if (maxBudgetPct == null) {
                maxBudgetPct = new BigDecimal("5.0");
            }
            if (maxEquityPctLocked == null) {
                maxEquityPctLocked = BigDecimal.ONE;
            }
        }
    }

    @Data
    public static class Alerts {
        private boolean enabled = true;
        private BigDecimal volume = new BigDecimal("0.9");
        private int durationSeconds = 10;

        public void ensureDefaults() {
            if (volume == null) {
                volume = new BigDecimal("0.9");
            }
            if (durationSeconds < 1) {
                durationSeconds = 10;
            }
        }
    }

    @Data
    public static class Ai {
        private boolean enabled = true;
        private List<String> allowlist = new ArrayList<>();
        private Mode live = new Mode();
        private Mode demo = new Mode();

        public void ensureDefaults() {
            if (allowlist == null) {
                allowlist = new ArrayList<>();
            }
            if (live == null) {
                live = new Mode();
            }
            if (demo == null) {
                demo = new Mode();
            }
            live.ensureDefaults();
            demo.ensureDefaults();
        }
    }

    @Data
    public static class Mode {
        private Routing routing = new Routing();

        public void ensureDefaults() {
            if (routing == null) {
                routing = new Routing();
            }
            routing.ensureDefaults();
        }
    }

    @Data
    public static class Routing {
        private TaskRouting suggestion = new TaskRouting();
        private TaskRouting explainability = new TaskRouting();
        private TaskRouting vision = new TaskRouting();
        private TaskRouting scanReview = new TaskRouting();

        public void ensureDefaults() {
            if (suggestion == null) {
                suggestion = new TaskRouting();
            }
            if (explainability == null) {
                explainability = new TaskRouting();
            }
            if (vision == null) {
                vision = new TaskRouting();
            }
            if (scanReview == null) {
                scanReview = new TaskRouting();
            }
            suggestion.ensureDefaults();
            explainability.ensureDefaults();
            vision.ensureDefaults();
            scanReview.ensureDefaults();
        }
    }

    @Data
    public static class TaskRouting {
        private String primaryModel;
        private List<String> fallbackModels = new ArrayList<>();

        public void ensureDefaults() {
            if (fallbackModels == null) {
                fallbackModels = new ArrayList<>();
            }
        }
    }

    @Data
    public static class DemoTrading {
        private boolean enabled = false;
        private int intervalMinutes = 15;
        private int maxOpenPositions = 1;
        private BigDecimal startBalanceUsdt = new BigDecimal("1000");
        private BigDecimal riskPct = new BigDecimal("0.5");
        private int leverageDefault = 5;
        private int feeBps = 4;
        private int slippageBps = 2;
        private int timeStopMinutes = 90;

        public void ensureDefaults() {
            if (startBalanceUsdt == null) {
                startBalanceUsdt = new BigDecimal("1000");
            }
            if (riskPct == null) {
                riskPct = new BigDecimal("0.5");
            }
            if (intervalMinutes < 1) {
                intervalMinutes = 15;
            }
            if (maxOpenPositions < 1) {
                maxOpenPositions = 1;
            }
            if (leverageDefault < 1) {
                leverageDefault = 5;
            }
            if (timeStopMinutes < 1) {
                timeStopMinutes = 90;
            }
            if (feeBps < 0) {
                feeBps = 0;
            }
            if (slippageBps < 0) {
                slippageBps = 0;
            }
        }
    }

    @Data
    public static class LiveExecution {
        private boolean readOnly = false;

        public void ensureDefaults() {
            // default false keeps manual execution available when capability is enabled
        }
    }

    @Data
    public static class BudgetTargetAutoExecution {
        public static final int LOCKED_MAX_CONCURRENT_POSITIONS = 3;
        public static final boolean LOCKED_ALLOW_CLOSE_ALL_ON_TARGET = true;

        private boolean enabled = false;
        private boolean armed = false;
        private boolean readOnly = false;
        @JsonAlias("maxActiveTrades")
        private int maxConcurrentPositions = LOCKED_MAX_CONCURRENT_POSITIONS;
        @JsonAlias("sessionBudgetUsdt")
        private BigDecimal defaultBudgetUsdt = new BigDecimal("50");
        @JsonAlias("finalTargetNetProfitUsdt")
        private BigDecimal defaultTargetProfitUsdt = new BigDecimal("10");
        private boolean allowNewSessionStart = true;
        private boolean allowCloseAllOnTarget = LOCKED_ALLOW_CLOSE_ALL_ON_TARGET;
        private boolean killSwitch = false;
        private boolean requireBinanceHealthPass = true;
        private boolean requireOperatorConfirmationForStop = true;
        private int sessionTimeoutMinutes = 240;

        public void ensureDefaults() {
            if (defaultBudgetUsdt == null) {
                defaultBudgetUsdt = new BigDecimal("50");
            }
            if (defaultTargetProfitUsdt == null) {
                defaultTargetProfitUsdt = new BigDecimal("10");
            }
            if (maxConcurrentPositions < 1) {
                maxConcurrentPositions = LOCKED_MAX_CONCURRENT_POSITIONS;
            }
            if (sessionTimeoutMinutes < 1) {
                sessionTimeoutMinutes = 240;
            }
        }

        public boolean normalizeLockedInvariants() {
            boolean changed = false;
            if (maxConcurrentPositions != LOCKED_MAX_CONCURRENT_POSITIONS) {
                maxConcurrentPositions = LOCKED_MAX_CONCURRENT_POSITIONS;
                changed = true;
            }
            if (allowCloseAllOnTarget != LOCKED_ALLOW_CLOSE_ALL_ON_TARGET) {
                allowCloseAllOnTarget = LOCKED_ALLOW_CLOSE_ALL_ON_TARGET;
                changed = true;
            }
            return changed;
        }

        @JsonIgnore
        public BigDecimal getSessionBudgetUsdt() {
            return defaultBudgetUsdt;
        }

        @JsonIgnore
        public void setSessionBudgetUsdt(BigDecimal sessionBudgetUsdt) {
            this.defaultBudgetUsdt = sessionBudgetUsdt;
        }

        @JsonIgnore
        public BigDecimal getFinalTargetNetProfitUsdt() {
            return defaultTargetProfitUsdt;
        }

        @JsonIgnore
        public void setFinalTargetNetProfitUsdt(BigDecimal finalTargetNetProfitUsdt) {
            this.defaultTargetProfitUsdt = finalTargetNetProfitUsdt;
        }

        @JsonIgnore
        public int getMaxActiveTrades() {
            return maxConcurrentPositions;
        }

        @JsonIgnore
        public void setMaxActiveTrades(int maxActiveTrades) {
            this.maxConcurrentPositions = maxActiveTrades;
        }
    }

    @Data
    public static class StrategyLocks {
        private String executionTf = "15m";
        private String biasTf = "1h";
        private int fractalPeriod = 5;
        private BigDecimal minRr = new BigDecimal("2.0");

        public void ensureDefaults() {
            if (executionTf == null || executionTf.isBlank()) {
                executionTf = "15m";
            }
            if (biasTf == null || biasTf.isBlank()) {
                biasTf = "1h";
            }
            if (minRr == null) {
                minRr = new BigDecimal("2.0");
            }
            if (fractalPeriod < 1) {
                fractalPeriod = 5;
            }
        }
    }
}
