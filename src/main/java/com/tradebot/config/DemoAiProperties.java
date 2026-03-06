package com.tradebot.config;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Data
@Validated
@ConfigurationProperties(prefix = "demo-ai")
public class DemoAiProperties {

    private boolean enabled = true;
    private RoutingOverrides routing;

    @Data
    public static class RoutingOverrides {
        private TaskRoutingOverride suggestion;
        private TaskRoutingOverride explainability;
        private VisionRoutingOverride vision;
    }

    @Data
    public static class TaskRoutingOverride {
        private String primaryModel;
        private List<String> fallbackModels;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class VisionRoutingOverride extends TaskRoutingOverride {
        private Boolean enabled;
    }
}
