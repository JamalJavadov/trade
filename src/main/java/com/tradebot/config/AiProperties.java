package com.tradebot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private OpenRouter openrouter = new OpenRouter();
    private Safety safety = new Safety();

    @Data
    public static class OpenRouter {
        private String apiKey;
        private String baseUrl = "https://openrouter.ai/api/v1";
    }

    @Data
    public static class Safety {
        private int maxRetriesPerCall = 2;
        private int requestTimeoutMs = 20_000;
        private int maxInputChars = 20_000;
        private boolean requireStrictJsonForSuggestion = true;
    }
}
