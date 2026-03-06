package com.tradebot;

import com.tradebot.config.AiProperties;
import com.tradebot.config.DemoAiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPropertiesBindingTest {

    @Test
    void bindsAiAndDemoAiRoutingProperties() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("ai.enabled", "true")
                .withProperty("ai.openrouter.api-key", "new-key")
                .withProperty("ai.routing.suggestion.primary-model", "model-primary")
                .withProperty("ai.routing.suggestion.fallback-models[0]", "model-f1")
                .withProperty("demo-ai.enabled", "true")
                .withProperty("demo-ai.routing.suggestion.primary-model", "demo-model");

        AiProperties aiProperties = Binder.get(environment)
                .bind("ai", Bindable.of(AiProperties.class))
                .orElseThrow(() -> new NoSuchElementException("ai properties not bound"));

        DemoAiProperties demoAiProperties = Binder.get(environment)
                .bind("demo-ai", Bindable.of(DemoAiProperties.class))
                .orElseThrow(() -> new NoSuchElementException("demoAi properties not bound"));

        assertTrue(aiProperties.isEnabled());
        assertEquals("new-key", aiProperties.getOpenrouter().getApiKey());
        assertEquals("model-primary", aiProperties.getRouting().getSuggestion().getPrimaryModel());
        assertEquals("model-f1", aiProperties.getRouting().getSuggestion().getFallbackModels().get(0));

        assertTrue(demoAiProperties.isEnabled());
        assertEquals("demo-model", demoAiProperties.getRouting().getSuggestion().getPrimaryModel());
    }

    @Test
    void bindsCamelCaseDemoAiRootAsRelaxedName() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("demoAi.enabled", "true")
                .withProperty("demoAi.routing.suggestion.primary-model", "demo-model-camel");

        DemoAiProperties demoAiProperties = Binder.get(environment)
                .bind("demo-ai", Bindable.of(DemoAiProperties.class))
                .orElseThrow(() -> new NoSuchElementException("demoAi properties not bound"));

        assertTrue(demoAiProperties.isEnabled());
        assertEquals("demo-model-camel", demoAiProperties.getRouting().getSuggestion().getPrimaryModel());
    }
}
