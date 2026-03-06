package com.tradebot;

import com.tradebot.demo.service.DemoAiSuggestionService;
import com.tradebot.demo.service.DemoStrategyConfigService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DemoAiIsolationGuardTest {

    private static final Set<String> FORBIDDEN_TYPES = Set.of(
            "com.tradebot.repository.AiSuggestionBatchRepository",
            "com.tradebot.repository.AiSuggestionItemRepository",
            "com.tradebot.repository.StrategyConfigVersionRepository",
            "com.tradebot.entity.AiSuggestionBatch",
            "com.tradebot.entity.AiSuggestionItem",
            "com.tradebot.entity.StrategyConfigVersion");

    @Test
    void demoAiSuggestionServiceDoesNotDependOnLiveAiTypes() {
        assertNoForbiddenTypes(DemoAiSuggestionService.class);
    }

    @Test
    void demoStrategyConfigServiceDoesNotDependOnLiveAiTypes() {
        assertNoForbiddenTypes(DemoStrategyConfigService.class);
    }

    private void assertNoForbiddenTypes(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            assertFalse(
                    FORBIDDEN_TYPES.contains(field.getType().getName()),
                    () -> type.getSimpleName() + " field uses live type: " + field.getType().getName());
        }

        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (Class<?> parameterType : constructor.getParameterTypes()) {
                assertFalse(
                        FORBIDDEN_TYPES.contains(parameterType.getName()),
                        () -> type.getSimpleName() + " constructor uses live type: " + parameterType.getName());
            }
        }
    }
}
