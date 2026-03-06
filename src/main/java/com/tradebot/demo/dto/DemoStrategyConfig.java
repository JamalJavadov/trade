package com.tradebot.demo.dto;

import com.tradebot.dto.StrategyTuningConfig;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DemoStrategyConfig {

    private String executionTf = "15m";
    private String biasTf = "1h";
    private int fractalPeriod = 5;
    private BigDecimal minRr = new BigDecimal("2.0");
    private Risk risk = new Risk();
    private StrategyTuningConfig tuning = new StrategyTuningConfig();

    @Data
    public static class Risk {
        private BigDecimal maxEquityPct = new BigDecimal("1.0");
    }
}
