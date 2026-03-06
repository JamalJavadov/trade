package com.tradebot.ai;

import com.tradebot.config.AiProperties;
import com.tradebot.config.AppProperties;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AiRoutingResolver {

    private static final String DEFAULT_MODEL = "openai/gpt-oss-120b:free";

    private final AiProperties aiProperties;
    private final AppProperties appProperties;
    private final ControlCenterSettingsProvider controlCenterSettingsProvider;

    public TaskRoute resolveTaskRoute(AiTaskType taskType, AiMode mode) {
        ControlCenterConfig.TaskRouting taskRouting = controlCenterSettingsProvider.taskRouting(mode, taskType);
        List<String> allowlist = controlCenterSettingsProvider.allowlist(mode);

        String primary = firstNonBlank(
                taskRouting.getPrimaryModel(),
                allowlist.isEmpty() ? null : allowlist.get(0),
                DEFAULT_MODEL);

        List<String> fallbackModels = new ArrayList<>();
        if (taskRouting.getFallbackModels() != null) {
            for (String fallback : taskRouting.getFallbackModels()) {
                String normalized = trimToNull(fallback);
                if (normalized == null || normalized.equals(primary)) {
                    continue;
                }
                if (!allowlist.contains(normalized)) {
                    continue;
                }
                if (!fallbackModels.contains(normalized)) {
                    fallbackModels.add(normalized);
                }
            }
        }

        List<String> deduped = dedupeChain(primary, fallbackModels);
        String selectedPrimary = deduped.isEmpty() ? DEFAULT_MODEL : deduped.get(0);
        List<String> selectedFallbacks = deduped.size() > 1
                ? new ArrayList<>(deduped.subList(1, deduped.size()))
                : List.of();

        return new TaskRoute(selectedPrimary, selectedFallbacks, true);
    }

    public String resolveApiKey() {
        return firstNonBlank(aiProperties.getOpenrouter().getApiKey(), appProperties.getOpenrouter().getApiKey());
    }

    public String resolveBaseUrl() {
        return firstNonBlank(aiProperties.getOpenrouter().getBaseUrl(), "https://openrouter.ai/api/v1");
    }

    public AiProperties.Safety safety() {
        return aiProperties.getSafety();
    }

    public boolean isAiEnabled(AiMode mode) {
        return controlCenterSettingsProvider.isAiEnabled(mode);
    }

    private List<String> dedupeChain(String primary, List<String> fallbackModels) {
        Set<String> chain = new LinkedHashSet<>();
        if (primary != null && !primary.isBlank()) {
            chain.add(primary.trim());
        }
        if (fallbackModels != null) {
            for (String model : fallbackModels) {
                if (model != null && !model.isBlank()) {
                    chain.add(model.trim());
                }
            }
        }
        return new ArrayList<>(chain);
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Data
    @AllArgsConstructor
    public static class TaskRoute {
        private String primaryModel;
        private List<String> fallbackModels;
        private boolean enabled;

        public List<String> modelChain() {
            List<String> chain = new ArrayList<>();
            if (primaryModel != null && !primaryModel.isBlank()) {
                chain.add(primaryModel);
            }
            if (fallbackModels != null) {
                for (String model : fallbackModels) {
                    if (model != null && !model.isBlank() && !chain.contains(model)) {
                        chain.add(model);
                    }
                }
            }
            return chain;
        }
    }
}
