package com.tradebot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.ai.AiMode;
import com.tradebot.ai.AiRequest;
import com.tradebot.ai.AiTaskType;
import com.tradebot.client.OpenRouterClient;
import com.tradebot.config.AiProperties;
import com.tradebot.dto.AiDiagnosticsRequestDTO;
import com.tradebot.dto.AiDiagnosticsResponseDTO;
import com.tradebot.exception.AiProviderException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiDiagnosticsService {

    private static final String JSON_BLOCK_PREFIX = "```json";
    private static final String JSON_BLOCK_SUFFIX = "```";

    private final OpenRouterClient openRouterClient;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final AiDiagnosticsSanitizer sanitizer;
    private final AiModelSettingsService aiModelSettingsService;

    public AiDiagnosticsResponseDTO diagnose(AiMode mode, AiDiagnosticsRequestDTO request, String traceId) {
        AiRequest aiRequest = buildRequest(mode, request);
        List<String> models = resolveModelChain(mode, aiRequest.getTaskType());

        for (String model : models) {
            try {
                String prompt = aiRequest.getSystemPrompt() + "\n\n" + aiRequest.getUserPrompt();
                String rawResponse = openRouterClient.generateSuggestions(prompt, model, traceId);
                AiDiagnosticsResponseDTO parsed = parseDiagnostics(rawResponse);
                AiDiagnosticsResponseDTO sanitizedResponse = sanitizer.sanitizeResponse(parsed);
                if (isUsable(sanitizedResponse)) {
                    return sanitizedResponse;
                }
                throw new IllegalArgumentException("Diagnostics response missing required fields.");
            } catch (AiProviderException ex) {
                log.warn("AI diagnostics provider failure mode={} model={} code={} traceId={} message={}",
                        mode, model, ex.getErrorCode(), traceId, sanitizer.sanitizeText(ex.getMessage(), 300));
            } catch (Exception ex) {
                log.warn("AI diagnostics parse/runtime failure mode={} model={} traceId={} message={}",
                        mode, model, traceId, sanitizer.sanitizeText(ex.getMessage(), 300));
            }
        }

        return sanitizer.sanitizeResponse(AiDiagnosticsResponseDTO.aiUnavailable());
    }

    private AiRequest buildRequest(AiMode mode, AiDiagnosticsRequestDTO request) {
        int maxInputChars = Math.max(512, aiProperties.getSafety().getMaxInputChars());

        String title = sanitizer.sanitizeText(request.getTitle(), 200);
        String contextText = sanitizer.sanitizeText(request.getContextText(), maxInputChars);
        String logs = sanitizer.sanitizeText(request.getLogs(), maxInputChars);
        Map<String, Object> constraints = sanitizer.sanitizeConstraints(request.getConstraints());

        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (contextText == null || contextText.isBlank()) {
            throw new IllegalArgumentException("contextText is required");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", mode.name());
        payload.put("title", title);
        payload.put("contextText", contextText);
        payload.put("logs", logs);
        payload.put("constraints", constraints);

        String systemPrompt = """
                You are a diagnostics assistant for a Binance trading UI.
                You are read-only. You must never suggest placing orders, changing strategy parameters, or executing trades.
                Return ONLY valid JSON with this exact schema:
                {
                  "summary":"string",
                  "probableRootCause":"string",
                  "checks":[{"step":"string","why":"string","expected":"string"}],
                  "fixes":[{"fix":"string","risk":"low|med|high"}]
                }
                Keep checks practical and specific.
                Do not include secrets, keys, or sensitive values.
                """;

        String jsonSchemaHint = """
                summary:string, probableRootCause:string, checks:array(step,why,expected), fixes:array(fix,risk=low|med|high)
                """;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", mode.name());
        metadata.put("futureVisionTaskType", AiTaskType.VISION_DIAGNOSTIC.name());

        return AiRequest.builder()
                .taskType(selectTaskType(request))
                .systemPrompt(systemPrompt)
                .userPrompt("Input JSON:\n" + toJson(payload))
                .jsonSchemaHint(jsonSchemaHint)
                .temperature(0.1)
                .maxTokens(1100)
                .metadata(metadata)
                .build();
    }

    private AiTaskType selectTaskType(AiDiagnosticsRequestDTO request) {
        return AiTaskType.EXPLAINABILITY_TEXT;
    }

    private List<String> resolveModelChain(AiMode mode, AiTaskType taskType) {
        LinkedHashSet<String> models = new LinkedHashSet<>(aiModelSettingsService.resolveModelChain(mode, taskType));
        return new ArrayList<>(models);
    }

    private AiDiagnosticsResponseDTO parseDiagnostics(String rawResponse) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
        String content = contentNode.asText(null);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("OpenRouter returned empty diagnostics payload.");
        }

        String cleaned = content.replace(JSON_BLOCK_PREFIX, "").replace(JSON_BLOCK_SUFFIX, "").trim();
        JsonNode payload = objectMapper.readTree(cleaned);

        String summary = requiredText(payload, "summary");
        String probableRootCause = requiredText(payload, "probableRootCause");

        JsonNode checksNode = payload.path("checks");
        JsonNode fixesNode = payload.path("fixes");
        if (!checksNode.isArray() || checksNode.isEmpty()) {
            throw new IllegalArgumentException("checks must be a non-empty array");
        }
        if (!fixesNode.isArray() || fixesNode.isEmpty()) {
            throw new IllegalArgumentException("fixes must be a non-empty array");
        }

        List<AiDiagnosticsResponseDTO.CheckItemDTO> checks = new ArrayList<>();
        for (JsonNode node : checksNode) {
            checks.add(new AiDiagnosticsResponseDTO.CheckItemDTO(
                    requiredText(node, "step"),
                    requiredText(node, "why"),
                    requiredText(node, "expected")));
        }

        List<AiDiagnosticsResponseDTO.FixItemDTO> fixes = new ArrayList<>();
        for (JsonNode node : fixesNode) {
            fixes.add(new AiDiagnosticsResponseDTO.FixItemDTO(
                    requiredText(node, "fix"),
                    node.path("risk").asText("med")));
        }

        return new AiDiagnosticsResponseDTO(summary, probableRootCause, checks, fixes);
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value;
    }

    private boolean isUsable(AiDiagnosticsResponseDTO response) {
        return response != null
                && response.getSummary() != null
                && !response.getSummary().isBlank()
                && response.getProbableRootCause() != null
                && !response.getProbableRootCause().isBlank()
                && response.getChecks() != null
                && !response.getChecks().isEmpty()
                && response.getFixes() != null
                && !response.getFixes().isEmpty();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
