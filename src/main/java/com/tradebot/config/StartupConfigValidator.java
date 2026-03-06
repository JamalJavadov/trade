package com.tradebot.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class StartupConfigValidator implements ApplicationRunner {

    private static final int MIN_AI_TIMEOUT_MS = 1_000;

    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        List<String> missing = new ArrayList<>();
        requireNonBlank("spring.datasource.url", missing);
        requireNonBlank("spring.datasource.username", missing);
        requireNonBlank("spring.datasource.password", missing);

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Missing critical configuration properties: " + String.join(", ", missing)
                            + ". Configure datasource values in src/main/resources/application.yml before startup.");
        }

        String password = environment.getProperty("spring.datasource.password", "");
        if (isPlaceholderPassword(password)) {
            throw new IllegalStateException(
                    "Invalid datasource password configuration: replace placeholder value in "
                            + "src/main/resources/application.yml with your local Postgres password.");
        }

        int timeoutMs = environment.getProperty("ai.safety.request-timeout-ms", Integer.class, 20_000);
        if (timeoutMs < MIN_AI_TIMEOUT_MS) {
            throw new IllegalStateException(
                    "Invalid configuration: ai.safety.request-timeout-ms must be >= " + MIN_AI_TIMEOUT_MS);
        }
    }

    private void requireNonBlank(String key, List<String> missing) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            missing.add(key);
        }
    }

    private boolean isPlaceholderPassword(String value) {
        if (value == null) {
            return true;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.isEmpty()
                || normalized.startsWith("change-me")
                || normalized.startsWith("your-password");
    }
}
