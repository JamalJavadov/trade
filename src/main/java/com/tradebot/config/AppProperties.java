package com.tradebot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import lombok.Data;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private Binance binance = new Binance();
    private OpenRouter openrouter = new OpenRouter();
    private Scanner scanner = new Scanner();
    private Strategy strategy = new Strategy();
    private Risk risk = new Risk();
    private Budget budget = new Budget();
    private String workingType = "MARK_PRICE";
    private Integer leverage = 5;

    @Data
    public static class Binance {
        private String apiKey;
        private String apiSecret;
    }

    @Data
    public static class OpenRouter {
        private String apiKey;
        private String model = "arcee-ai/trinity-large-preview:free";
        private boolean modelControlsEnabled = false;
        private List<String> allowedModels = new ArrayList<>();
    }

    @Data
    public static class Scanner {
        private int intervalMinutes = 20;
        private int topN = 300;
        private int exchangeInfoMaxInMemoryBytes = 2 * 1024 * 1024;
    }

    @Data
    public static class Strategy {
        private int fractalPeriod = 5;
        private int maxSweepCandles = 3;
        private int invalidationCandles = 5;
        private BigDecimal minRr = new BigDecimal("2.0");
        private BigDecimal slBufferPct = new BigDecimal("0.0005");
    }

    @Data
    public static class Risk {
        private BigDecimal maxEquityPct = new BigDecimal("1.0");
        private BigDecimal maxBudgetPct = new BigDecimal("5.0");
        private BigDecimal equityOverrideUsdt;
    }

    @Data
    public static class Budget {
        private BigDecimal usdt = new BigDecimal("50");
    }
}
