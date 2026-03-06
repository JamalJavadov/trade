package com.tradebot;

import com.tradebot.ai.AiMode;
import com.tradebot.ai.AiRoutingResolver;
import com.tradebot.ai.AiTaskType;
import com.tradebot.controller.AiDiagnosticsController;
import com.tradebot.dto.AiDiagnosticsResponseDTO;
import com.tradebot.exception.GlobalExceptionHandler;
import com.tradebot.service.AiDiagnosticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AiDiagnosticsController.class)
@Import(GlobalExceptionHandler.class)
class AiDiagnosticsControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiDiagnosticsService aiDiagnosticsService;

    @MockBean
    private AiRoutingResolver aiRoutingResolver;

    @Test
    void diagnosticsReturns200AndStructuredJson() throws Exception {
        mockVisionEnabled(true);
        when(aiDiagnosticsService.diagnose(eq(AiMode.LIVE), any(), anyString()))
                .thenReturn(new AiDiagnosticsResponseDTO(
                        "Detected websocket mismatch",
                        "Stale symbol subscription",
                        List.of(new AiDiagnosticsResponseDTO.CheckItemDTO(
                                "Refresh symbol stream",
                                "Ensures active channel matches selected symbol",
                                "Live updates resume")),
                        List.of(new AiDiagnosticsResponseDTO.FixItemDTO(
                                "Reconnect websocket",
                                "low"))));

        mockMvc.perform(post("/api/v1/ai/diagnostics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"UI issue",
                                  "contextText":"Panel not updating",
                                  "logs":"ws timeout",
                                  "constraints":{"exchange":"binance"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("Detected websocket mismatch"))
                .andExpect(jsonPath("$.probableRootCause").value("Stale symbol subscription"))
                .andExpect(jsonPath("$.checks[0].step").value("Refresh symbol stream"))
                .andExpect(jsonPath("$.fixes[0].risk").value("low"));
    }

    @Test
    void diagnosticsReturns403WhenVisionFlagDisabled() throws Exception {
        mockVisionEnabled(false);

        mockMvc.perform(post("/api/v1/ai/diagnostics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"UI issue",
                                  "contextText":"Panel not updating"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.summary").value("AI unavailable"))
                .andExpect(jsonPath("$.checks[0].step").exists())
                .andExpect(jsonPath("$.fixes[0].risk").exists());
    }

    @Test
    void diagnosticsFallbackSerializationIsStable() throws Exception {
        mockVisionEnabled(true);
        when(aiDiagnosticsService.diagnose(eq(AiMode.LIVE), any(), anyString()))
                .thenReturn(AiDiagnosticsResponseDTO.aiUnavailable());

        mockMvc.perform(post("/api/v1/ai/diagnostics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"Binance error",
                                  "contextText":"request failed"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("AI unavailable"))
                .andExpect(jsonPath("$.probableRootCause").exists())
                .andExpect(jsonPath("$.checks[0].why").exists())
                .andExpect(jsonPath("$.fixes[0].fix").exists());
    }

    private void mockVisionEnabled(boolean enabled) {
        when(aiRoutingResolver.resolveTaskRoute(eq(AiTaskType.VISION_DIAGNOSTIC), eq(AiMode.LIVE)))
                .thenReturn(new AiRoutingResolver.TaskRoute(
                        "model-live-vision",
                        List.of(),
                        enabled));
    }
}
