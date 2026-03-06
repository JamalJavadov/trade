package com.tradebot.config;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "demo-trading")
public class DemoTradingProperties {

    @NotNull
    private BinanceCredentials binance = new BinanceCredentials();

    @Data
    public static class BinanceCredentials {
        private String apiKey;
        private String apiSecret;
    }
}
