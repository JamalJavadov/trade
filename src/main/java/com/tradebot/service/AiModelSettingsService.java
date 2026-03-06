package com.tradebot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradebot.ai.AiMode;
import com.tradebot.ai.AiTaskType;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.demo.entity.DemoAiSuggestionBatch;
import com.tradebot.demo.repository.DemoAiSuggestionBatchRepository;
import com.tradebot.dto.AiModelTaskUpdateDTO;
import com.tradebot.dto.AiModelsResponseDTO;
import com.tradebot.dto.AiModelsTestRequestDTO;
import com.tradebot.dto.AiModelsTestResponseDTO;
import com.tradebot.dto.AiModelsUpdateRequestDTO;
import com.tradebot.entity.AiSuggestionBatch;
import com.tradebot.repository.AiSuggestionBatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiModelSettingsService {

    private static final String CALL_STATUS_SUCCESS = "SUCCESS";
    private static final String CALL_STATUS_FAILED = "FAILED";

    private final ControlCenterSettingsProvider controlCenterSettingsProvider;
    private final AiSuggestionBatchRepository aiSuggestionBatchRepository;
    private final DemoAiSuggestionBatchRepository demoAiSuggestionBatchRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public AiModelsResponseDTO getLiveSettings() {
        return buildResponse(AiMode.LIVE);
    }

    @Transactional(readOnly = true)
    public AiModelsResponseDTO getDemoSettings() {
        return buildResponse(AiMode.DEMO);
    }

    @Transactional
    public AiModelsResponseDTO updateLiveSettings(AiModelsUpdateRequestDTO request) {
        updateRouting(AiMode.LIVE, request);
        return buildResponse(AiMode.LIVE);
    }

    @Transactional
    public AiModelsResponseDTO updateDemoSettings(AiModelsUpdateRequestDTO request) {
        updateRouting(AiMode.DEMO, request);
        return buildResponse(AiMode.DEMO);
    }

    @Transactional(readOnly = true)
    public ResolvedTaskRoute resolveEffectiveRoute(AiMode mode, AiTaskType taskType) {
        ControlCenterConfig.TaskRouting route = controlCenterSettingsProvider.taskRouting(mode, taskType);
        List<String> allowlist = controlCenterSettingsProvider.allowlist(mode);
        TaskRoute validated = validateRoute(mode, route.getPrimaryModel(), route.getFallbackModels(), allowlist);
        return new ResolvedTaskRoute(mode, taskType, validated.primaryModel(), validated.fallbackModels());
    }

    @Transactional(readOnly = true)
    public List<String> resolveModelChain(AiMode mode, AiTaskType taskType) {
        ResolvedTaskRoute route = resolveEffectiveRoute(mode, taskType);
        List<String> chain = new ArrayList<>();
        if (route.primaryModel() != null && !route.primaryModel().isBlank()) {
            chain.add(route.primaryModel());
        }
        for (String fallback : route.fallbackModels()) {
            if (fallback != null && !fallback.isBlank() && !chain.contains(fallback)) {
                chain.add(fallback);
            }
        }
        return chain;
    }

    @Transactional(readOnly = true)
    public AiModelsTestResponseDTO simulateTest(AiMode mode, AiModelsTestRequestDTO request) {
        AiTaskType taskType = request != null && request.getTaskType() != null
                ? request.getTaskType()
                : AiTaskType.SUGGESTION_BATCH;

        ResolvedTaskRoute route = resolveEffectiveRoute(mode, taskType);
        AiModelsTestResponseDTO response = new AiModelsTestResponseDTO();
        response.setMode(mode.name());
        response.setTaskType(taskType);
        response.setOk(true);
        response.setSimulated(true);
        response.setTraceId("test-" + UUID.randomUUID().toString().substring(0, 8));
        response.setLatencyMs(0L);
        response.setModelUsed(route.primaryModel());
        response.setPayload(Map.of("message", "Dummy AI model test response"));
        return response;
    }

    private AiModelsResponseDTO buildResponse(AiMode mode) {
        ResolvedTaskRoute route = resolveEffectiveRoute(mode, AiTaskType.SUGGESTION_BATCH);

        AiModelsResponseDTO response = new AiModelsResponseDTO();
        response.setMode(mode.name());
        response.setControlsEnabled(true);
        response.setAllowlist(controlCenterSettingsProvider.allowlist(mode));

        AiModelsResponseDTO.TaskModelDTO task = new AiModelsResponseDTO.TaskModelDTO();
        task.setTaskType(AiTaskType.SUGGESTION_BATCH);
        task.setPrimaryModel(route.primaryModel());
        task.setFallbackModels(new ArrayList<>(route.fallbackModels()));
        task.setLastCall(lastCall(mode));
        response.setTasks(List.of(task));
        return response;
    }

    private AiModelsResponseDTO.LastCallDTO lastCall(AiMode mode) {
        if (mode == AiMode.LIVE) {
            return aiSuggestionBatchRepository.findFirstByOrderByCreatedAtDesc()
                    .map(this::toLastCall)
                    .orElse(null);
        }
        return demoAiSuggestionBatchRepository.findFirstByOrderByCreatedAtDesc()
                .map(this::toLastCall)
                .orElse(null);
    }

    private AiModelsResponseDTO.LastCallDTO toLastCall(AiSuggestionBatch batch) {
        AiModelsResponseDTO.LastCallDTO dto = new AiModelsResponseDTO.LastCallDTO();
        dto.setStatus(normalizeCallStatus(batch.getCallStatus(), batch.getStatus()));
        dto.setTraceId(batch.getTraceId());
        dto.setLatencyMs(batch.getLatencyMs());
        dto.setModelUsed(batch.getModel());
        dto.setCalledAt(batch.getCreatedAt());
        return dto;
    }

    private AiModelsResponseDTO.LastCallDTO toLastCall(DemoAiSuggestionBatch batch) {
        AiModelsResponseDTO.LastCallDTO dto = new AiModelsResponseDTO.LastCallDTO();
        dto.setStatus(normalizeCallStatus(batch.getCallStatus(), batch.getStatus()));
        dto.setTraceId(batch.getTraceId());
        dto.setLatencyMs(batch.getLatencyMs());
        dto.setModelUsed(batch.getModel());
        dto.setCalledAt(batch.getCreatedAt());
        return dto;
    }

    private String normalizeCallStatus(String callStatus, String batchStatus) {
        if (callStatus != null && !callStatus.isBlank()) {
            return callStatus;
        }
        return "FAILED".equalsIgnoreCase(batchStatus) ? CALL_STATUS_FAILED : CALL_STATUS_SUCCESS;
    }

    private void updateRouting(AiMode mode, AiModelsUpdateRequestDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("Request payload is required");
        }

        List<String> allowlist = controlCenterSettingsProvider.allowlist(mode);
        TaskRoute targetRoute;

        if (request.isRevertToDefaults()) {
            targetRoute = new TaskRoute(allowlist.get(0), List.of());
        } else {
            targetRoute = validateUpdateTask(mode, request.getTasks(), allowlist);
        }

        ObjectNode aiRoutingPatch = objectMapper.createObjectNode();
        ObjectNode modePatch = aiRoutingPatch.putObject(mode == AiMode.LIVE ? "live" : "demo");
        ObjectNode tasksPatch = modePatch.putObject("tasks");
        ObjectNode suggestionPatch = tasksPatch.putObject(AiTaskType.SUGGESTION_BATCH.name());
        suggestionPatch.put("primaryModel", targetRoute.primaryModel());
        ArrayNode fallbackNode = suggestionPatch.putArray("fallbackModels");
        for (String fallback : targetRoute.fallbackModels()) {
            fallbackNode.add(fallback);
        }

        controlCenterSettingsProvider.patchState(null, aiRoutingPatch);
    }

    private TaskRoute validateUpdateTask(AiMode mode, List<AiModelTaskUpdateDTO> tasks, List<String> allowlist) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("At least one task route is required");
        }
        AiModelTaskUpdateDTO suggestion = tasks.stream()
                .filter(task -> task != null && task.getTaskType() == AiTaskType.SUGGESTION_BATCH)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("SUGGESTION_BATCH task is required"));

        return validateRoute(mode, suggestion.getPrimaryModel(), suggestion.getFallbackModels(), allowlist);
    }

    private TaskRoute validateRoute(AiMode mode, String primaryModel, List<String> fallbackModels, List<String> allowlist) {
        String primary = normalizeModel(primaryModel, "primaryModel");
        if (!allowlist.contains(primary)) {
            throw new IllegalArgumentException("primaryModel is not allowlisted for " + mode.name() + ": " + primary);
        }

        List<String> normalizedFallbacks = new ArrayList<>();
        if (fallbackModels != null) {
            for (String rawFallback : fallbackModels) {
                String fallback = normalizeModel(rawFallback, "fallbackModels");
                if (!allowlist.contains(fallback)) {
                    throw new IllegalArgumentException("fallback model is not allowlisted for " + mode.name() + ": " + fallback);
                }
                if (primary.equals(fallback)) {
                    throw new IllegalArgumentException("fallbackModels must not include primaryModel");
                }
                if (normalizedFallbacks.contains(fallback)) {
                    throw new IllegalArgumentException("fallbackModels contains duplicate model: " + fallback);
                }
                normalizedFallbacks.add(fallback);
            }
        }
        return new TaskRoute(primary, normalizedFallbacks);
    }

    private String normalizeModel(String rawModel, String field) {
        if (rawModel == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        String model = rawModel.trim();
        if (model.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return model;
    }

    public record ResolvedTaskRoute(AiMode mode, AiTaskType taskType, String primaryModel, List<String> fallbackModels) {
    }

    private record TaskRoute(String primaryModel, List<String> fallbackModels) {
        private TaskRoute {
            LinkedHashSet<String> dedup = new LinkedHashSet<>(fallbackModels == null ? List.of() : fallbackModels);
            fallbackModels = new ArrayList<>(dedup);
        }
    }
}
