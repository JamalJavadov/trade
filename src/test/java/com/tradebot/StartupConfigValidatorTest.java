package com.tradebot;

import com.tradebot.config.StartupConfigValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupConfigValidatorTest {

    @Test
    void throwsWhenCriticalDatasourceConfigMissing() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/trade-bot");

        StartupConfigValidator validator = new StartupConfigValidator(environment);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> validator.run(new DefaultApplicationArguments(new String[0])));
        assertTrue(ex.getMessage().contains("Missing critical configuration properties"));
        assertTrue(ex.getMessage().contains("spring.datasource.username"));
        assertTrue(ex.getMessage().contains("spring.datasource.password"));
    }

    @Test
    void throwsWhenAiTimeoutIsBelowMinimum() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/trade-bot")
                .withProperty("spring.datasource.username", "trade-bot")
                .withProperty("spring.datasource.password", "secret")
                .withProperty("ai.safety.request-timeout-ms", "500");

        StartupConfigValidator validator = new StartupConfigValidator(environment);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> validator.run(new DefaultApplicationArguments(new String[0])));
        assertTrue(ex.getMessage().contains("ai.safety.request-timeout-ms"));
    }

    @Test
    void acceptsValidCriticalConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/trade-bot")
                .withProperty("spring.datasource.username", "trade-bot")
                .withProperty("spring.datasource.password", "secret")
                .withProperty("ai.safety.request-timeout-ms", "20000");

        StartupConfigValidator validator = new StartupConfigValidator(environment);

        assertDoesNotThrow(() -> validator.run(new DefaultApplicationArguments(new String[0])));
    }

    @Test
    void throwsWhenPlaceholderPasswordIsUsed() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/trade-bot")
                .withProperty("spring.datasource.username", "trade-bot")
                .withProperty("spring.datasource.password", "change-me-local-postgres-password")
                .withProperty("ai.safety.request-timeout-ms", "20000");

        StartupConfigValidator validator = new StartupConfigValidator(environment);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> validator.run(new DefaultApplicationArguments(new String[0])));
        assertTrue(ex.getMessage().contains("replace placeholder value"));
    }
}
