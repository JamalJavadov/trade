package com.tradebot;

import com.tradebot.ai.AiMode;
import com.tradebot.ai.AiRoutingResolver;
import com.tradebot.ai.AiTaskType;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.demo.controller.DemoAiDiagnosticsController;
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

@WebMvcTest(controllers = DemoAiDiagnosticsController.class)
@Import(GlobalExceptionHandler.class)
class DemoAiDiagnosticsControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiDiagnosticsService aiDiagnosticsService;

    @MockBean
    private AiRoutingResolver aiRoutingResolver;

    @MockBean
    private ControlCenterSettingsProvider controlCenterSettingsProvider;

    @Test
    void diagnosticsReturns200WhenFlagsAllowRequest() throws Exception {
        mockVisionEnabled(true);
        mockDemoEnabled(true);
        when(aiDiagnosticsService.diagnose(eq(AiMode.DEMO), any(), anyString()))
                .thenReturn(new AiDiagnosticsResponseDTO(
                        "Demo diagnostics summary",
                        "Likely stale market data cache",
                        List.of(new AiDiagnosticsResponseDTO.CheckItemDTO(
                                "Clear cache",
                                "Removes stale demo cache entries",
                                "New snapshot is loaded")),
                        List.of(new AiDiagnosticsResponseDTO.FixItemDTO(
                                "Run a fresh demo cycle",
                                "low"))));

        mockMvc.perform(post("/api/v1/demo-trading/ai/diagnostics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"Demo issue",
                                  "contextText":"price line frozen"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("Demo diagnostics summary"))
                .andExpect(jsonPath("$.probableRootCause").value("Likely stale market data cache"));
    }

    @Test
    void diagnosticsReturns409WhenDemoDisabled() throws Exception {
        mockVisionEnabled(true);
        mockDemoEnabled(false);

        mockMvc.perform(post("/api/v1/demo-trading/ai/diagnostics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"Demo issue",
                                  "contextText":"price line frozen"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.summary").value("AI unavailable"))
                .andExpect(jsonPath("$.probableRootCause").value("Demo diagnostics is disabled because demo-trading.enabled=false."));
    }

    @Test
    void diagnosticsReturns403WhenVisionFlagDisabled() throws Exception {
        mockVisionEnabled(false);
        mockDemoEnabled(true);

        mockMvc.perform(post("/api/v1/demo-trading/ai/diagnostics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"Demo issue",
                                  "contextText":"price line frozen"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.summary").value("AI unavailable"))
                .andExpect(jsonPath("$.checks[0].step").exists());
    }

    private void mockVisionEnabled(boolean enabled) {
        when(aiRoutingResolver.resolveTaskRoute(eq(AiTaskType.VISION_DIAGNOSTIC), eq(AiMode.DEMO)))
                .thenReturn(new AiRoutingResolver.TaskRoute(
                        "model-demo-vision",
                        List.of(),
                        enabled));
    }

    private void mockDemoEnabled(boolean enabled) {
        ControlCenterConfig config = new ControlCenterConfig();
        config.getDemoTrading().setEnabled(enabled);
        when(controlCenterSettingsProvider.getConfigSnapshot()).thenReturn(config);
    }
}
