package com.tradebot;

import com.tradebot.ai.AiMode;
import com.tradebot.ai.AiRoutingResolver;
import com.tradebot.ai.AiTaskType;
import com.tradebot.config.AiProperties;
import com.tradebot.config.AppProperties;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiRoutingResolverTest {

    @Test
    void routeUsesDbTaskRoutingAndAllowlistFiltering() {
        AiProperties aiProperties = new AiProperties();
        AppProperties appProperties = new AppProperties();
        appProperties.getOpenrouter().setModel("legacy-model");
        ControlCenterSettingsProvider provider = mock(ControlCenterSettingsProvider.class);

        ControlCenterConfig.TaskRouting taskRouting = new ControlCenterConfig.TaskRouting();
        taskRouting.setPrimaryModel("demo-primary");
        taskRouting.setFallbackModels(List.of("demo-fallback", "demo-primary", "not-allowlisted"));
        taskRouting.setEnabled(true);

        when(provider.taskRouting(AiMode.DEMO, AiTaskType.SUGGESTION_BATCH)).thenReturn(taskRouting);
        when(provider.allowlist(AiMode.DEMO)).thenReturn(List.of("demo-primary", "demo-fallback"));

        AiRoutingResolver resolver = new AiRoutingResolver(aiProperties, appProperties, provider);

        AiRoutingResolver.TaskRoute route = resolver.resolveTaskRoute(AiTaskType.SUGGESTION_BATCH, AiMode.DEMO);
        assertEquals("demo-primary", route.getPrimaryModel());
        assertEquals(List.of("demo-fallback"), route.getFallbackModels());
        assertFalse(route.getFallbackModels().contains("not-allowlisted"));
    }

    @Test
    void legacyApiKeyAndModelFallbackWhenDbRouteMissing() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.getOpenrouter().setApiKey("");
        AppProperties appProperties = new AppProperties();
        appProperties.getOpenrouter().setApiKey("legacy-key");
        appProperties.getOpenrouter().setModel("legacy-live-model");
        ControlCenterSettingsProvider provider = mock(ControlCenterSettingsProvider.class);

        ControlCenterConfig.TaskRouting taskRouting = new ControlCenterConfig.TaskRouting();
        taskRouting.setPrimaryModel(null);
        taskRouting.setFallbackModels(List.of());
        taskRouting.setEnabled(true);

        when(provider.taskRouting(AiMode.LIVE, AiTaskType.SUGGESTION_BATCH)).thenReturn(taskRouting);
        when(provider.allowlist(AiMode.LIVE)).thenReturn(List.of());

        AiRoutingResolver resolver = new AiRoutingResolver(aiProperties, appProperties, provider);

        assertEquals("legacy-key", resolver.resolveApiKey());
        assertEquals("legacy-live-model",
                resolver.resolveTaskRoute(AiTaskType.SUGGESTION_BATCH, AiMode.LIVE).getPrimaryModel());
    }
}
