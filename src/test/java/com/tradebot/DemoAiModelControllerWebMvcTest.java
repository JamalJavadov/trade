package com.tradebot;

import com.tradebot.ai.AiMode;
import com.tradebot.ai.AiTaskType;
import com.tradebot.demo.controller.DemoAiModelController;
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

@WebMvcTest(controllers = DemoAiModelController.class)
@Import(GlobalExceptionHandler.class)
class DemoAiModelControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiModelSettingsService aiModelSettingsService;

    @MockBean
    private LocalMutationGuard localMutationGuard;

    @Test
    void getModelsReturnsResponse() throws Exception {
        when(aiModelSettingsService.getDemoSettings()).thenReturn(response("DEMO"));

        mockMvc.perform(get("/api/v1/demo-trading/ai/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("DEMO"))
                .andExpect(jsonPath("$.tasks[0].taskType").value("SUGGESTION_BATCH"));
    }

    @Test
    void updateModelsReturnsUpdatedResponse() throws Exception {
        when(aiModelSettingsService.updateDemoSettings(any())).thenReturn(response("DEMO"));

        mockMvc.perform(post("/api/v1/demo-trading/ai/models")
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
        dto.setMode(AiMode.DEMO.name());
        dto.setTaskType(AiTaskType.SUGGESTION_BATCH);
        dto.setOk(true);
        dto.setSimulated(true);
        dto.setTraceId("test-456");
        dto.setLatencyMs(0L);
        dto.setModelUsed("model-a");
        dto.setPayload(Map.of("message", "Dummy AI model test response"));
        when(aiModelSettingsService.simulateTest(any(), any())).thenReturn(dto);

        mockMvc.perform(post("/api/v1/demo-trading/ai/models/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskType\":\"SUGGESTION_BATCH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("DEMO"))
                .andExpect(jsonPath("$.payload.message").value("Dummy AI model test response"));
    }

    private AiModelsResponseDTO response(String mode) {
        AiModelsResponseDTO dto = new AiModelsResponseDTO();
        dto.setMode(mode);
        dto.setControlsEnabled(true);
        dto.setAllowlist(List.of("model-a", "model-b"));

        AiModelsResponseDTO.TaskModelDTO task = new AiModelsResponseDTO.TaskModelDTO();
        task.setTaskType(AiTaskType.SUGGESTION_BATCH);
        task.setPrimaryModel("model-a");
        task.setFallbackModels(List.of("model-b"));
        dto.setTasks(List.of(task));
        return dto;
    }
}
