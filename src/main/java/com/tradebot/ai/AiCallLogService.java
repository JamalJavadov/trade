package com.tradebot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.tradebot.demo.entity.DemoAiCallLog;
import com.tradebot.demo.repository.DemoAiCallLogRepository;
import com.tradebot.entity.AiCallLog;
import com.tradebot.repository.AiCallLogRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiCallLogService {

    private static final Pattern SECRET_TOKEN_PATTERN = Pattern.compile("sk-[A-Za-z0-9_-]+");
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._-]+");

    private final AiCallLogRepository aiCallLogRepository;
    private final DemoAiCallLogRepository demoAiCallLogRepository;
    private final ObjectMapper objectMapper;
    private final AiRoutingResolver routingResolver;
    private final MeterRegistry meterRegistry;

    public void logAttempt(
            AiMode mode,
            AiRequest request,
            ModelAttemptResult attempt,
            String traceId) {

        String promptJson = sanitizeJson(toPromptPayload(request));
        String responseJson = sanitizeJson(toResponsePayload(attempt));

        if (mode == AiMode.DEMO) {
            DemoAiCallLog row = new DemoAiCallLog();
            row.setCreatedAt(Instant.now());
            row.setTaskType(request.getTaskType().name());
            row.setModelRequested(attempt.getModelRequested());
            row.setModelUsed(attempt.getModelUsed());
            row.setStatus(attempt.getStatus().name());
            row.setErrorCode(attempt.getErrorCode() != null ? attempt.getErrorCode().name() : null);
            row.setErrorMessage(sanitizeText(attempt.getErrorMessage(), null));
            row.setLatencyMs(attempt.getLatencyMs());
            row.setTraceId(traceId);
            row.setPromptSanitizedJson(promptJson);
            row.setResponseSanitizedJson(responseJson);
            demoAiCallLogRepository.save(row);
        } else {
            AiCallLog row = new AiCallLog();
            row.setCreatedAt(Instant.now());
            row.setTaskType(request.getTaskType().name());
            row.setProvider("openrouter.ai");
            row.setModel(attempt.getModelUsed() != null ? attempt.getModelUsed() : attempt.getModelRequested());
            row.setModelRequested(attempt.getModelRequested());
            row.setModelUsed(attempt.getModelUsed());
            row.setStatus(attempt.getStatus().name());
            row.setErrorCode(attempt.getErrorCode() != null ? attempt.getErrorCode().name() : null);
            row.setErrorMessage(sanitizeText(attempt.getErrorMessage(), null));
            row.setHttpStatus(httpStatusForCode(attempt.getErrorCode()));
            row.setLatencyMs(attempt.getLatencyMs());
            row.setTraceId(traceId);
            row.setPromptSanitizedJson(promptJson);
            row.setResponseSanitizedJson(responseJson);
            row.setPromptText(sanitizeText(request.getUserPrompt(), "prompt"));
            row.setResponseText(sanitizeText(attempt.getResponse() != null
                    ? attempt.getResponse().getRawJson()
                    : attempt.getErrorMessage(), "response"));
            aiCallLogRepository.save(row);
        }

        String errorCodeTag = attempt.getErrorCode() != null ? attempt.getErrorCode().name() : "NONE";
        Counter.builder("ai_calls_total")
                .tag("mode", mode.name())
                .tag("task_type", request.getTaskType().name())
                .tag("model", attempt.getModelRequested())
                .tag("status", attempt.getStatus().name())
                .tag("error_code", errorCodeTag)
                .register(meterRegistry)
                .increment();

        Timer.builder("ai_call_latency")
                .tag("mode", mode.name())
                .tag("task_type", request.getTaskType().name())
                .tag("model", attempt.getModelRequested())
                .tag("status", attempt.getStatus().name())
                .register(meterRegistry)
                .record(Duration.ofMillis(Math.max(0L, attempt.getLatencyMs())));
    }

    private Map<String, Object> toPromptPayload(AiRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskType", request.getTaskType() != null ? request.getTaskType().name() : null);
        payload.put("systemPrompt", request.getSystemPrompt());
        payload.put("userPrompt", request.getUserPrompt());
        payload.put("jsonSchemaHint", request.getJsonSchemaHint());
        payload.put("temperature", request.getTemperature());
        payload.put("maxTokens", request.getMaxTokens());
        payload.put("metadata", request.getMetadata());
        return payload;
    }

    private Map<String, Object> toResponsePayload(ModelAttemptResult attempt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", attempt.getStatus().name());
        payload.put("modelRequested", attempt.getModelRequested());
        payload.put("modelUsed", attempt.getModelUsed());
        payload.put("latencyMs", attempt.getLatencyMs());
        payload.put("errorCode", attempt.getErrorCode() != null ? attempt.getErrorCode().name() : null);
        payload.put("errorMessage", attempt.getErrorMessage());

        AiResponse response = attempt.getResponse();
        if (response != null) {
            payload.put("text", response.getText());
            payload.put("tokensIn", response.getTokensIn());
            payload.put("tokensOut", response.getTokensOut());
            payload.put("providerRequestId", response.getProviderRequestId());
            payload.put("traceId", response.getTraceId());

            JsonNode rawNode = null;
            if (response.getRawJson() != null && !response.getRawJson().isBlank()) {
                try {
                    rawNode = objectMapper.readTree(response.getRawJson());
                } catch (Exception e) {
                    rawNode = TextNode.valueOf(response.getRawJson());
                }
            }
            payload.put("raw", rawNode);
        }

        return payload;
    }

    private String sanitizeJson(Object raw) {
        try {
            JsonNode node = toJsonNode(raw);
            JsonNode sanitized = sanitizeNode(node, null);
            return objectMapper.writeValueAsString(sanitized);
        } catch (Exception e) {
            log.warn("Failed to sanitize AI payload: {}", e.getMessage());
            return "{}";
        }
    }

    private JsonNode toJsonNode(Object raw) {
        if (raw == null) {
            return JsonNodeFactory.instance.objectNode();
        }
        if (raw instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        if (raw instanceof byte[]) {
            return TextNode.valueOf("[binary omitted]");
        }
        return objectMapper.valueToTree(raw);
    }

    private JsonNode sanitizeNode(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }

        if (node.isTextual()) {
            return TextNode.valueOf(sanitizeText(node.asText(), fieldName));
        }

        if (node.isBinary()) {
            return TextNode.valueOf("[binary omitted]");
        }

        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                out.set(entry.getKey(), sanitizeNode(entry.getValue(), entry.getKey()));
            }
            return out;
        }

        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode child : node) {
                out.add(sanitizeNode(child, fieldName));
            }
            return out;
        }

        return node;
    }

    private String sanitizeText(String value, String fieldName) {
        if (value == null) {
            return null;
        }

        String lowered = fieldName == null ? "" : fieldName.toLowerCase();
        if (lowered.contains("apikey")
                || lowered.contains("api_key")
                || lowered.contains("authorization")
                || lowered.contains("token")
                || lowered.contains("secret")
                || lowered.contains("password")) {
            return "***";
        }

        String masked = SECRET_TOKEN_PATTERN.matcher(value).replaceAll("***");
        masked = BEARER_TOKEN_PATTERN.matcher(masked).replaceAll("Bearer ***");

        int maxChars = Math.max(1, routingResolver.safety().getMaxInputChars());
        if (masked.length() > maxChars) {
            return masked.substring(0, maxChars);
        }
        return masked;
    }

    private Integer httpStatusForCode(com.tradebot.exception.ErrorCode code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case OPENROUTER_AUTH -> 401;
            case OPENROUTER_RATE_LIMIT -> 429;
            case OPENROUTER_BAD_JSON -> 422;
            case OPENROUTER_NETWORK, OPENROUTER_INTERNAL -> 503;
            default -> null;
        };
    }
}
