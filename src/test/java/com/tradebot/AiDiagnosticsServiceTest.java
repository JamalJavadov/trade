package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.ai.AiMode;
import com.tradebot.ai.AiTaskType;
import com.tradebot.client.OpenRouterClient;
import com.tradebot.config.AiProperties;
import com.tradebot.dto.AiDiagnosticsRequestDTO;
import com.tradebot.dto.AiDiagnosticsResponseDTO;
import com.tradebot.exception.AiProviderException;
import com.tradebot.exception.ErrorCode;
import com.tradebot.service.AiDiagnosticsSanitizer;
import com.tradebot.service.AiDiagnosticsService;
import com.tradebot.service.AiModelSettingsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiDiagnosticsServiceTest {

    @Test
    void diagnoseReturnsParsedResponseWhenProviderReturnsValidJson() {
        OpenRouterClient openRouterClient = mock(OpenRouterClient.class);
        AiProperties aiProperties = properties("model-primary", List.of());
        AiDiagnosticsService service = new AiDiagnosticsService(
                openRouterClient,
                aiProperties,
                new ObjectMapper(),
                new AiDiagnosticsSanitizer(),
                modelSettings(aiProperties));

        String modelOutput = """
                {
                  "summary":"UI liquidation panel failing due to stale websocket state.",
                  "probableRootCause":"Frontend subscribed to outdated symbol stream.",
                  "checks":[{"step":"Reload stream subscription","why":"Re-initializes channel mapping","expected":"Current symbol receives fresh events"}],
                  "fixes":[{"fix":"Reconnect websocket and clear stale subscription cache","risk":"medium"}]
                }
                """;
        when(openRouterClient.generateSuggestions(anyString(), eq("model-primary"), eq("trace-1")))
                .thenReturn(openRouterPayload(modelOutput));

        AiDiagnosticsResponseDTO response = service.diagnose(AiMode.LIVE, request(), "trace-1");

        assertEquals("UI liquidation panel failing due to stale websocket state.", response.getSummary());
        assertEquals("Frontend subscribed to outdated symbol stream.", response.getProbableRootCause());
        assertEquals(1, response.getChecks().size());
        assertEquals(1, response.getFixes().size());
        assertEquals("med", response.getFixes().get(0).getRisk());
    }

    @Test
    void diagnoseReturnsAiUnavailableWhenProviderFails() {
        OpenRouterClient openRouterClient = mock(OpenRouterClient.class);
        AiProperties aiProperties = properties("model-primary", List.of());
        AiDiagnosticsService service = new AiDiagnosticsService(
                openRouterClient,
                aiProperties,
                new ObjectMapper(),
                new AiDiagnosticsSanitizer(),
                modelSettings(aiProperties));

        when(openRouterClient.generateSuggestions(anyString(), eq("model-primary"), eq("trace-1")))
                .thenThrow(new AiProviderException(
                        ErrorCode.OPENROUTER_RATE_LIMIT,
                        429,
                        "openrouter.ai",
                        "OpenRouter rate limit exceeded",
                        "30"));

        AiDiagnosticsResponseDTO response = service.diagnose(AiMode.LIVE, request(), "trace-1");

        assertEquals("AI unavailable", response.getSummary());
        assertFalse(response.getChecks().isEmpty());
        assertFalse(response.getFixes().isEmpty());
    }

    @Test
    void diagnoseReturnsAiUnavailableWhenProviderPayloadInvalid() {
        OpenRouterClient openRouterClient = mock(OpenRouterClient.class);
        AiProperties aiProperties = properties("model-primary", List.of());
        AiDiagnosticsService service = new AiDiagnosticsService(
                openRouterClient,
                aiProperties,
                new ObjectMapper(),
                new AiDiagnosticsSanitizer(),
                modelSettings(aiProperties));

        when(openRouterClient.generateSuggestions(anyString(), eq("model-primary"), eq("trace-1")))
                .thenReturn(openRouterPayload("not-json"));

        AiDiagnosticsResponseDTO response = service.diagnose(AiMode.LIVE, request(), "trace-1");

        assertEquals("AI unavailable", response.getSummary());
        assertFalse(response.getChecks().isEmpty());
    }

    @Test
    void diagnoseNormalizesRiskToLowMedHigh() {
        OpenRouterClient openRouterClient = mock(OpenRouterClient.class);
        AiProperties aiProperties = properties("model-primary", List.of());
        AiDiagnosticsService service = new AiDiagnosticsService(
                openRouterClient,
                aiProperties,
                new ObjectMapper(),
                new AiDiagnosticsSanitizer(),
                modelSettings(aiProperties));

        String modelOutput = """
                {
                  "summary":"Issue detected.",
                  "probableRootCause":"Incorrect endpoint base URL.",
                  "checks":[{"step":"Check base URL","why":"Wrong host causes request failures","expected":"Base URL points to expected backend host"}],
                  "fixes":[
                    {"fix":"Switch to valid host","risk":"medium"},
                    {"fix":"Retry request","risk":"UNKNOWN"}
                  ]
                }
                """;
        when(openRouterClient.generateSuggestions(anyString(), eq("model-primary"), eq("trace-1")))
                .thenReturn(openRouterPayload(modelOutput));

        AiDiagnosticsResponseDTO response = service.diagnose(AiMode.LIVE, request(), "trace-1");

        assertEquals("med", response.getFixes().get(0).getRisk());
        assertEquals("med", response.getFixes().get(1).getRisk());
    }

    @Test
    void diagnoseSanitizesPromptAndResponseSecrets() {
        OpenRouterClient openRouterClient = mock(OpenRouterClient.class);
        AiProperties aiProperties = properties("model-primary", List.of());
        AiDiagnosticsService service = new AiDiagnosticsService(
                openRouterClient,
                aiProperties,
                new ObjectMapper(),
                new AiDiagnosticsSanitizer(),
                modelSettings(aiProperties));

        AiDiagnosticsRequestDTO request = request();
        request.setLogs("Authorization: Bearer token-value sk-real-secret");
        request.setContextText("API_KEY=live-secret-value");
        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("password", "my-pass");
        request.setConstraints(constraints);

        String modelOutput = """
                {
                  "summary":"token=abc sk-real-secret",
                  "probableRootCause":"secret leak in logs",
                  "checks":[{"step":"Mask secrets","why":"Avoid data leaks","expected":"No cleartext secrets present"}],
                  "fixes":[{"fix":"Redact tokens before upload","risk":"low"}]
                }
                """;
        when(openRouterClient.generateSuggestions(anyString(), eq("model-primary"), eq("trace-1")))
                .thenReturn(openRouterPayload(modelOutput));

        AiDiagnosticsResponseDTO response = service.diagnose(AiMode.LIVE, request, "trace-1");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(openRouterClient).generateSuggestions(promptCaptor.capture(), eq("model-primary"), eq("trace-1"));
        String prompt = promptCaptor.getValue();
        assertFalse(prompt.contains("live-secret-value"));
        assertFalse(prompt.contains("my-pass"));
        assertFalse(prompt.contains("sk-real-secret"));
        assertTrue(prompt.contains("***"));

        assertNotNull(response.getSummary());
        assertFalse(response.getSummary().contains("sk-real-secret"));
        assertFalse(response.getSummary().contains("token=abc"));
    }

    @Test
    void diagnoseTriesFallbackModelAfterPrimaryFailure() {
        OpenRouterClient openRouterClient = mock(OpenRouterClient.class);
        AiProperties aiProperties = properties("model-primary", List.of("model-fallback"));
        AiDiagnosticsService service = new AiDiagnosticsService(
                openRouterClient,
                aiProperties,
                new ObjectMapper(),
                new AiDiagnosticsSanitizer(),
                modelSettings(aiProperties));

        when(openRouterClient.generateSuggestions(anyString(), eq("model-primary"), eq("trace-1")))
                .thenThrow(new AiProviderException(
                        ErrorCode.OPENROUTER_NETWORK,
                        503,
                        "openrouter.ai",
                        "timeout",
                        null));
        when(openRouterClient.generateSuggestions(anyString(), eq("model-fallback"), eq("trace-1")))
                .thenReturn(openRouterPayload("""
                        {
                          "summary":"Recovered via fallback model.",
                          "probableRootCause":"Primary model unavailable.",
                          "checks":[{"step":"Observe retry behavior","why":"Fallback was used","expected":"One successful fallback response"}],
                          "fixes":[{"fix":"Keep fallback chain configured","risk":"low"}]
                        }
                        """));

        AiDiagnosticsResponseDTO response = service.diagnose(AiMode.DEMO, request(), "trace-1");

        assertEquals("Recovered via fallback model.", response.getSummary());
        InOrder inOrder = inOrder(openRouterClient);
        inOrder.verify(openRouterClient).generateSuggestions(anyString(), eq("model-primary"), eq("trace-1"));
        inOrder.verify(openRouterClient).generateSuggestions(anyString(), eq("model-fallback"), eq("trace-1"));
    }

    private AiProperties properties(String primary, List<String> fallbacks) {
        AiProperties properties = new AiProperties();
        properties.getRouting().getExplainability().setPrimaryModel(primary);
        properties.getRouting().getExplainability().setFallbackModels(fallbacks);
        return properties;
    }

    private AiDiagnosticsRequestDTO request() {
        AiDiagnosticsRequestDTO request = new AiDiagnosticsRequestDTO();
        request.setTitle("Binance UI error");
        request.setContextText("Order panel shows stale status after reconnect.");
        request.setLogs("WebSocket disconnected once.");
        request.setConstraints(Map.of("locale", "en-US"));
        return request;
    }

    private String openRouterPayload(String content) {
        String escapedContent = content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return "{\"choices\":[{\"message\":{\"content\":\"" + escapedContent + "\"}}]}";
    }

    private AiModelSettingsService modelSettings(AiProperties aiProperties) {
        AiModelSettingsService settingsService = mock(AiModelSettingsService.class);
        List<String> chain = new ArrayList<>();
        String primary = aiProperties.getRouting().getExplainability().getPrimaryModel();
        if (primary != null && !primary.isBlank()) {
            chain.add(primary);
        }
        List<String> fallbacks = aiProperties.getRouting().getExplainability().getFallbackModels();
        if (fallbacks != null) {
            for (String fallback : fallbacks) {
                if (fallback != null && !fallback.isBlank() && !chain.contains(fallback)) {
                    chain.add(fallback);
                }
            }
        }
        when(settingsService.resolveModelChain(eq(AiMode.LIVE), eq(AiTaskType.EXPLAINABILITY_TEXT)))
                .thenReturn(chain);
        when(settingsService.resolveModelChain(eq(AiMode.DEMO), eq(AiTaskType.EXPLAINABILITY_TEXT)))
                .thenReturn(chain);
        return settingsService;
    }
}
