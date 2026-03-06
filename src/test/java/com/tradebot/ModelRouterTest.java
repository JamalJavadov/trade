package com.tradebot;

import com.tradebot.ai.AiCallLogService;
import com.tradebot.ai.AiCallStatus;
import com.tradebot.ai.AiMode;
import com.tradebot.ai.AiRequest;
import com.tradebot.ai.AiResponse;
import com.tradebot.ai.AiRoutingResolver;
import com.tradebot.ai.AiTaskType;
import com.tradebot.ai.ModelRouter;
import com.tradebot.ai.ModelRouterResult;
import com.tradebot.client.OpenRouterClient;
import com.tradebot.exception.AiProviderException;
import com.tradebot.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRouterTest {

    @Test
    void primarySuccessShortCircuitsFallbackChain() {
        OpenRouterClient client = mock(OpenRouterClient.class);
        AiRoutingResolver resolver = mock(AiRoutingResolver.class);
        AiCallLogService logService = mock(AiCallLogService.class);

        ModelRouter router = new ModelRouter(client, resolver, logService);
        AiRequest request = AiRequest.builder().taskType(AiTaskType.SUGGESTION_BATCH).userPrompt("u").build();

        when(client.generateCompletion(any(), anyString(), anyString())).thenReturn(AiResponse.builder()
                .modelUsed("model-a")
                .text("{}")
                .latencyMs(10)
                .traceId("trace-1")
                .build());

        ModelRouterResult result = router.runWithFallback(
                request,
                new AiRoutingResolver.TaskRoute("model-a", List.of("model-b"), true),
                AiMode.LIVE);

        assertTrue(result.isSuccess());
        assertEquals("model-a", result.getResponse().getModelUsed());
        verify(client, times(1)).generateCompletion(any(), anyString(), anyString());
        verify(logService, times(1)).logAttempt(any(), any(), any(), anyString());
    }

    @Test
    void retryableFailureFallsBackToNextModel() {
        OpenRouterClient client = mock(OpenRouterClient.class);
        AiRoutingResolver resolver = mock(AiRoutingResolver.class);
        AiCallLogService logService = mock(AiCallLogService.class);

        ModelRouter router = new ModelRouter(client, resolver, logService);
        AiRequest request = AiRequest.builder().taskType(AiTaskType.SUGGESTION_BATCH).userPrompt("u").build();

        when(client.generateCompletion(any(), anyString(), anyString()))
                .thenThrow(new AiProviderException(
                        ErrorCode.OPENROUTER_RATE_LIMIT,
                        429,
                        "openrouter.ai",
                        "rate limit",
                        null))
                .thenReturn(AiResponse.builder()
                        .modelUsed("model-b")
                        .text("{}")
                        .latencyMs(12)
                        .traceId("trace-2")
                        .build());

        ModelRouterResult result = router.runWithFallback(
                request,
                new AiRoutingResolver.TaskRoute("model-a", List.of("model-b"), true),
                AiMode.LIVE);

        assertTrue(result.isSuccess());
        assertEquals("model-b", result.getResponse().getModelUsed());
        assertEquals(2, result.getAttempts().size());
        assertEquals(AiCallStatus.FAILED, result.getAttempts().get(0).getStatus());
        assertEquals(AiCallStatus.SUCCESS, result.getAttempts().get(1).getStatus());
        verify(logService, times(2)).logAttempt(any(), any(), any(), anyString());
    }

    @Test
    void nonRetryableFailureStopsChain() {
        OpenRouterClient client = mock(OpenRouterClient.class);
        AiRoutingResolver resolver = mock(AiRoutingResolver.class);
        AiCallLogService logService = mock(AiCallLogService.class);

        ModelRouter router = new ModelRouter(client, resolver, logService);
        AiRequest request = AiRequest.builder().taskType(AiTaskType.SUGGESTION_BATCH).userPrompt("u").build();

        when(client.generateCompletion(any(), anyString(), anyString()))
                .thenThrow(new AiProviderException(
                        ErrorCode.OPENROUTER_AUTH,
                        401,
                        "openrouter.ai",
                        "auth failed",
                        null));

        ModelRouterResult result = router.runWithFallback(
                request,
                new AiRoutingResolver.TaskRoute("model-a", List.of("model-b"), true),
                AiMode.LIVE);

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.OPENROUTER_AUTH, result.getErrorCode());
        assertEquals(1, result.getAttempts().size());
        verify(client, times(1)).generateCompletion(any(), anyString(), anyString());
        verify(logService, times(1)).logAttempt(any(), any(), any(), anyString());
    }

    @Test
    void allFailuresReturnStructuredResultWithoutThrowing() {
        OpenRouterClient client = mock(OpenRouterClient.class);
        AiRoutingResolver resolver = mock(AiRoutingResolver.class);
        AiCallLogService logService = mock(AiCallLogService.class);

        ModelRouter router = new ModelRouter(client, resolver, logService);
        AiRequest request = AiRequest.builder().taskType(AiTaskType.SUGGESTION_BATCH).userPrompt("u").build();

        when(client.generateCompletion(any(), anyString(), anyString()))
                .thenThrow(new AiProviderException(
                        ErrorCode.OPENROUTER_RATE_LIMIT,
                        429,
                        "openrouter.ai",
                        "rate",
                        null))
                .thenThrow(new AiProviderException(
                        ErrorCode.OPENROUTER_INTERNAL,
                        500,
                        "openrouter.ai",
                        "internal",
                        null));

        ModelRouterResult result = router.runWithFallback(
                request,
                new AiRoutingResolver.TaskRoute("model-a", List.of("model-b"), true),
                AiMode.DEMO);

        assertFalse(result.isSuccess());
        assertEquals(ErrorCode.OPENROUTER_INTERNAL, result.getErrorCode());
        assertNotNull(result.getTraceId());
        assertEquals(2, result.getAttempts().size());
        verify(logService, times(2)).logAttempt(any(), any(), any(), anyString());
    }
}
