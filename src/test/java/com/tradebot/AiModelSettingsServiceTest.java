package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.ai.AiMode;
import com.tradebot.ai.AiTaskType;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.demo.entity.DemoAiSuggestionBatch;
import com.tradebot.demo.repository.DemoAiSuggestionBatchRepository;
import com.tradebot.dto.AiModelTaskUpdateDTO;
import com.tradebot.dto.AiModelsResponseDTO;
import com.tradebot.dto.AiModelsUpdateRequestDTO;
import com.tradebot.entity.AiSuggestionBatch;
import com.tradebot.repository.AiSuggestionBatchRepository;
import com.tradebot.service.AiModelSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiModelSettingsServiceTest {

    private ControlCenterSettingsProvider controlCenterSettingsProvider;
    private AiSuggestionBatchRepository aiSuggestionBatchRepository;
    private DemoAiSuggestionBatchRepository demoAiSuggestionBatchRepository;
    private AiModelSettingsService service;

    @BeforeEach
    void setUp() {
        controlCenterSettingsProvider = mock(ControlCenterSettingsProvider.class);
        aiSuggestionBatchRepository = mock(AiSuggestionBatchRepository.class);
        demoAiSuggestionBatchRepository = mock(DemoAiSuggestionBatchRepository.class);

        when(aiSuggestionBatchRepository.findFirstByOrderByCreatedAtDesc()).thenReturn(Optional.empty());
        when(demoAiSuggestionBatchRepository.findFirstByOrderByCreatedAtDesc()).thenReturn(Optional.empty());

        when(controlCenterSettingsProvider.allowlist(AiMode.LIVE))
                .thenReturn(List.of("model-live-a", "model-live-b"));
        when(controlCenterSettingsProvider.allowlist(AiMode.DEMO))
                .thenReturn(List.of("model-demo-a", "model-demo-b"));

        ControlCenterConfig.TaskRouting liveRoute = new ControlCenterConfig.TaskRouting();
        liveRoute.setPrimaryModel("model-live-a");
        liveRoute.setFallbackModels(List.of("model-live-b"));
        liveRoute.setEnabled(true);
        when(controlCenterSettingsProvider.taskRouting(AiMode.LIVE, AiTaskType.SUGGESTION_BATCH))
                .thenReturn(liveRoute);

        ControlCenterConfig.TaskRouting demoRoute = new ControlCenterConfig.TaskRouting();
        demoRoute.setPrimaryModel("model-demo-a");
        demoRoute.setFallbackModels(List.of("model-demo-b"));
        demoRoute.setEnabled(true);
        when(controlCenterSettingsProvider.taskRouting(AiMode.DEMO, AiTaskType.SUGGESTION_BATCH))
                .thenReturn(demoRoute);

        service = new AiModelSettingsService(
                controlCenterSettingsProvider,
                aiSuggestionBatchRepository,
                demoAiSuggestionBatchRepository,
                new ObjectMapper());
    }

    @Test
    void resolveRouteUsesProviderRouting() {
        AiModelSettingsService.ResolvedTaskRoute route = service.resolveEffectiveRoute(AiMode.LIVE, AiTaskType.SUGGESTION_BATCH);
        assertEquals("model-live-a", route.primaryModel());
        assertEquals(List.of("model-live-b"), route.fallbackModels());
    }

    @Test
    void updateLiveSettingsRejectsUnknownModel() {
        AiModelsUpdateRequestDTO request = new AiModelsUpdateRequestDTO();
        request.setRevertToDefaults(false);
        AiModelTaskUpdateDTO task = new AiModelTaskUpdateDTO();
        task.setTaskType(AiTaskType.SUGGESTION_BATCH);
        task.setPrimaryModel("unknown-model");
        task.setFallbackModels(List.of());
        request.setTasks(List.of(task));

        assertThrows(IllegalArgumentException.class, () -> service.updateLiveSettings(request));
    }

    @Test
    void updateLiveSettingsPersistsPatch() {
        AiModelsUpdateRequestDTO request = new AiModelsUpdateRequestDTO();
        request.setRevertToDefaults(false);
        AiModelTaskUpdateDTO task = new AiModelTaskUpdateDTO();
        task.setTaskType(AiTaskType.SUGGESTION_BATCH);
        task.setPrimaryModel("model-live-b");
        task.setFallbackModels(List.of("model-live-a"));
        request.setTasks(List.of(task));

        service.updateLiveSettings(request);

        verify(controlCenterSettingsProvider).patchState(any(), any());
    }

    @Test
    void getLiveSettingsIncludesLastCallTelemetry() {
        AiSuggestionBatch batch = new AiSuggestionBatch();
        batch.setCreatedAt(Instant.now());
        batch.setStatus("PROPOSED");
        batch.setCallStatus("SUCCESS");
        batch.setTraceId("trace-abc");
        batch.setLatencyMs(222L);
        batch.setModel("model-live-a");
        when(aiSuggestionBatchRepository.findFirstByOrderByCreatedAtDesc()).thenReturn(Optional.of(batch));

        AiModelsResponseDTO response = service.getLiveSettings();

        assertEquals("LIVE", response.getMode());
        assertEquals("SUCCESS", response.getTasks().get(0).getLastCall().getStatus());
        assertEquals("trace-abc", response.getTasks().get(0).getLastCall().getTraceId());
    }

    @Test
    void getDemoSettingsIncludesLastCallTelemetry() {
        DemoAiSuggestionBatch batch = new DemoAiSuggestionBatch();
        batch.setCreatedAt(Instant.now());
        batch.setStatus("FAILED");
        batch.setCallStatus("FAILED");
        batch.setTraceId("trace-demo");
        batch.setLatencyMs(99L);
        batch.setModel("model-demo-a");
        when(demoAiSuggestionBatchRepository.findFirstByOrderByCreatedAtDesc()).thenReturn(Optional.of(batch));

        AiModelsResponseDTO response = service.getDemoSettings();

        assertEquals("DEMO", response.getMode());
        assertEquals("FAILED", response.getTasks().get(0).getLastCall().getStatus());
        assertEquals("trace-demo", response.getTasks().get(0).getLastCall().getTraceId());
    }
}
