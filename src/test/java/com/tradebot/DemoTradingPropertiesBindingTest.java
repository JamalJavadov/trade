package com.tradebot;

import com.tradebot.config.DemoTradingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.NoSuchElementException;

class DemoTradingPropertiesBindingTest {

    @Test
    void bindsDemoBinanceCredentialsFromEnvironmentVariables() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("demo-trading.enabled", "true")
                .withProperty("demo-trading.binance.api-key", "demo-key-123")
                .withProperty("demo-trading.binance.api-secret", "demo-secret-456");

        DemoTradingProperties properties = Binder.get(environment)
                .bind("demo-trading", Bindable.of(DemoTradingProperties.class))
                .orElseThrow(() -> new NoSuchElementException("demo-trading properties not bound"));

        assertTrue(properties.isEnabled());
        assertEquals("demo-key-123", properties.getBinance().getApiKey());
        assertEquals("demo-secret-456", properties.getBinance().getApiSecret());
    }
}
