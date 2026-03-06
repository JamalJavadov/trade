package com.tradebot;

import com.tradebot.ai.AiMode;
import com.tradebot.ai.AiTaskType;
import com.tradebot.controller.AiModelController;
import com.tradebot.dto.AiModelsResponseDTO;
import com.tradebot.dto.AiModelsTestResponseDTO;
import com.tradebot.exception.GlobalExceptionHandler;
import com.tradebot.security.LocalMutationGuard;
import com.tradebot.service.AiModelSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AiModelController.class)
@Import(GlobalExceptionHandler.class)
class AiModelControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiModelSettingsService aiModelSettingsService;

    @MockBean
    private LocalMutationGuard localMutationGuard;

    @Test
    void getModelsReturnsResponse() throws Exception {
        when(aiModelSettingsService.getLiveSettings()).thenReturn(response("LIVE"));

        mockMvc.perform(get("/api/v1/ai/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("LIVE"))
                .andExpect(jsonPath("$.tasks[0].taskType").value("SUGGESTION_BATCH"));
    }

    @Test
    void updateModelsReturnsUpdatedResponse() throws Exception {
        when(aiModelSettingsService.updateLiveSettings(any())).thenReturn(response("LIVE"));

        mockMvc.perform(post("/api/v1/ai/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "revertToDefaults": false,
                                  "tasks": [{
                                    "taskType": "SUGGESTION_BATCH",
                                    "primaryModel": "model-a",
                                    "fallbackModels": ["model-b"]
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowlist[0]").value("model-a"));
    }

    @Test
    void testCallReturnsDummyPayload() throws Exception {
        AiModelsTestResponseDTO dto = new AiModelsTestResponseDTO();
        dto.setMode(AiMode.LIVE.name());
        dto.setTaskType(AiTaskType.SUGGESTION_BATCH);
        dto.setOk(true);
        dto.setSimulated(true);
        dto.setTraceId("test-123");
        dto.setLatencyMs(0L);
        dto.setModelUsed("model-a");
        dto.setPayload(Map.of("message", "Dummy AI model test response"));
        when(aiModelSettingsService.simulateTest(any(), any())).thenReturn(dto);

        mockMvc.perform(post("/api/v1/ai/models/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskType\":\"SUGGESTION_BATCH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.payload.message").value("Dummy AI model test response"));
    }

    @Test
    void updateModelsWhenControlsDisabledReturnsConflict() throws Exception {
        when(aiModelSettingsService.updateLiveSettings(any()))
                .thenThrow(new IllegalStateException("AI model controls are disabled for mode LIVE"));

        mockMvc.perform(post("/api/v1/ai/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revertToDefaults\":false,\"tasks\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT_OR_STATE_ERROR"));
    }

    private AiModelsResponseDTO response(String mode) {
        AiModelsResponseDTO dto = new AiModelsResponseDTO();
        dto.setMode(mode);
        dto.setControlsEnabled(true);
        dto.setAllowlist(List.of("model-a", "model-b"));

        AiModelsResponseDTO.LastCallDTO lastCall = new AiModelsResponseDTO.LastCallDTO();
        lastCall.setStatus("SUCCESS");
        lastCall.setTraceId("trace-1");
        lastCall.setLatencyMs(100L);
        lastCall.setModelUsed("model-a");

        AiModelsResponseDTO.TaskModelDTO task = new AiModelsResponseDTO.TaskModelDTO();
        task.setTaskType(AiTaskType.SUGGESTION_BATCH);
        task.setPrimaryModel("model-a");
        task.setFallbackModels(List.of("model-b"));
        task.setLastCall(lastCall);

        dto.setTasks(List.of(task));
        return dto;
    }
}
