package com.tradebot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.ai.AiRequest;
import com.tradebot.ai.AiResponse;
import com.tradebot.ai.AiRoutingResolver;
import com.tradebot.ai.AiTaskType;
import com.tradebot.exception.AiProviderException;
import com.tradebot.exception.ErrorCode;
import com.tradebot.trace.TraceIdContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
@Slf4j
public class OpenRouterClient {

    private final AiRoutingResolver routingResolver;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public String generateSuggestions(String prompt) {
        return generateSuggestions(prompt, null);
    }

    public String generateSuggestions(String prompt, String traceId) {
        String defaultModel = routingResolver.resolveTaskRoute(
                AiTaskType.SUGGESTION_BATCH,
                com.tradebot.ai.AiMode.LIVE).getPrimaryModel();
        return generateSuggestions(prompt, defaultModel, traceId);
    }

    public String generateSuggestions(String prompt, String model, String traceId) {
        AiRequest request = AiRequest.builder()
                .taskType(AiTaskType.SUGGESTION_BATCH)
                .userPrompt(prompt)
                .build();

        AiResponse response = generateCompletion(request, model, traceId);
        return response.getRawJson();
    }

    public AiResponse generateCompletion(AiRequest request, String modelId, String traceId) {
        String effectiveTraceId = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        String apiKey = routingResolver.resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiProviderException(
                    ErrorCode.OPENROUTER_AUTH,
                    401,
                    "openrouter.ai",
                    "OpenRouter API key is missing",
                    null);
        }

        String baseUrl = routingResolver.resolveBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://openrouter.ai/api/v1";
        }

        WebClient webClient = webClientBuilder.baseUrl(baseUrl).build();
        Map<String, Object> requestBody = buildRequestBody(request, modelId);

        long startedAt = System.currentTimeMillis();
        try {
            String rawResponse = webClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header(TraceIdContext.TRACE_ID_HEADER, effectiveTraceId)
                    .header("HTTP-Referer", "http://localhost:8080")
                    .header("X-Title", "TradeBot")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(Math.max(1, routingResolver.safety().getRequestTimeoutMs())))
                    .onErrorMap(this::mapToAiProviderException)
                    .retryWhen(retrySpec())
                    .block();

            return parseResponse(rawResponse, modelId, effectiveTraceId, System.currentTimeMillis() - startedAt);
        } catch (AiProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AiProviderException(
                    ErrorCode.OPENROUTER_NETWORK,
                    503,
                    "openrouter.ai",
                    "OpenRouter request failed unexpectedly",
                    null,
                    ex);
        }
    }

    private Map<String, Object> buildRequestBody(AiRequest request, String modelId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);

        List<Map<String, String>> messages = new ArrayList<>();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.getSystemPrompt()));
        }

        StringBuilder userPrompt = new StringBuilder(request.getUserPrompt() == null ? "" : request.getUserPrompt());
        if (request.getJsonSchemaHint() != null && !request.getJsonSchemaHint().isBlank()) {
            userPrompt.append("\n\nJSON_SCHEMA_HINT:\n").append(request.getJsonSchemaHint());
        }
        messages.add(Map.of("role", "user", "content", userPrompt.toString()));

        body.put("messages", messages);

        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null && request.getMaxTokens() > 0) {
            body.put("max_tokens", request.getMaxTokens());
        }
        if (request.getMetadata() != null && !request.getMetadata().isEmpty()) {
            body.put("metadata", request.getMetadata());
        }

        return body;
    }

    private Retry retrySpec() {
        int retries = Math.max(0, routingResolver.safety().getMaxRetriesPerCall());
        return Retry.backoff(retries, Duration.ofMillis(300))
                .jitter(0.5)
                .filter(this::isRetryableThrowable)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    private boolean isRetryableThrowable(Throwable throwable) {
        if (throwable instanceof AiProviderException ex) {
            return ex.getErrorCode() == ErrorCode.OPENROUTER_RATE_LIMIT
                    || ex.getErrorCode() == ErrorCode.OPENROUTER_NETWORK
                    || ex.getErrorCode() == ErrorCode.OPENROUTER_INTERNAL;
        }
        return throwable instanceof TimeoutException || throwable instanceof WebClientRequestException;
    }

    private Throwable mapToAiProviderException(Throwable throwable) {
        if (throwable instanceof AiProviderException) {
            return throwable;
        }

        if (throwable instanceof WebClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String retryAfter = ex.getHeaders().getFirst("Retry-After");

            if (status == 401 || status == 403) {
                return new AiProviderException(
                        ErrorCode.OPENROUTER_AUTH,
                        status,
                        "openrouter.ai",
                        "OpenRouter authentication failed",
                        retryAfter,
                        ex);
            }

            if (status == 429) {
                return new AiProviderException(
                        ErrorCode.OPENROUTER_RATE_LIMIT,
                        status,
                        "openrouter.ai",
                        "OpenRouter rate limit exceeded",
                        retryAfter,
                        ex);
            }

            if (status >= 500) {
                return new AiProviderException(
                        ErrorCode.OPENROUTER_INTERNAL,
                        status,
                        "openrouter.ai",
                        "OpenRouter internal failure",
                        retryAfter,
                        ex);
            }

            return new AiProviderException(
                    ErrorCode.OPENROUTER_NETWORK,
                    status,
                    "openrouter.ai",
                    "OpenRouter API request failed",
                    retryAfter,
                    ex);
        }

        if (throwable instanceof WebClientRequestException || throwable instanceof TimeoutException) {
            return new AiProviderException(
                    ErrorCode.OPENROUTER_NETWORK,
                    503,
                    "openrouter.ai",
                    "OpenRouter network/timeout failure",
                    null,
                    throwable);
        }

        return throwable;
    }

    private AiResponse parseResponse(String rawResponse, String requestedModel, String traceId, long latencyMs) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            String content = contentNode.asText(null);
            if (content == null || content.isBlank()) {
                throw new AiProviderException(
                        ErrorCode.OPENROUTER_BAD_JSON,
                        422,
                        "openrouter.ai",
                        "OpenRouter returned empty content",
                        null);
            }

            String modelUsed = root.path("model").asText(requestedModel);
            JsonNode usage = root.path("usage");

            return AiResponse.builder()
                    .modelUsed(modelUsed)
                    .text(content)
                    .rawJson(rawResponse)
                    .tokensIn(numberOrNull(usage.path("prompt_tokens")))
                    .tokensOut(numberOrNull(usage.path("completion_tokens")))
                    .latencyMs(Math.max(0L, latencyMs))
                    .providerRequestId(root.path("id").asText(null))
                    .traceId(traceId)
                    .build();
        } catch (AiProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AiProviderException(
                    ErrorCode.OPENROUTER_BAD_JSON,
                    422,
                    "openrouter.ai",
                    "OpenRouter returned invalid JSON payload",
                    null,
                    ex);
        }
    }

    private Integer numberOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText());
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }
}
