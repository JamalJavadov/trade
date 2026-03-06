package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.ai.AiRequest;
import com.tradebot.ai.AiRoutingResolver;
import com.tradebot.ai.AiTaskType;
import com.tradebot.client.OpenRouterClient;
import com.tradebot.exception.AiProviderException;
import com.tradebot.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenRouterClientTest {

    @Test
    void missingApiKeyThrowsClassifiedAuthError() {
        AiRoutingResolver routingResolver = mock(AiRoutingResolver.class);
        when(routingResolver.resolveApiKey()).thenReturn("");

        OpenRouterClient client = new OpenRouterClient(
                routingResolver,
                mock(WebClient.Builder.class),
                new ObjectMapper());

        AiProviderException ex = assertThrows(AiProviderException.class,
                () -> client.generateCompletion(
                        AiRequest.builder()
                                .taskType(AiTaskType.SUGGESTION_BATCH)
                                .userPrompt("prompt")
                                .build(),
                        "openai/gpt-oss-120b:free",
                        "trace-1"));
        assertEquals(ErrorCode.OPENROUTER_AUTH, ex.getErrorCode());
        assertEquals(Integer.valueOf(401), ex.getHttpStatus());
    }

    @Test
    void mapsHttpErrorCodesToExpectedClassification() {
        OpenRouterClient client = newClient();

        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "30");

        Throwable auth = WebClientResponseException.create(
                401,
                "Unauthorized",
                headers,
                new byte[0],
                StandardCharsets.UTF_8,
                new MockClientHttpRequest(HttpMethod.POST, URI.create("https://openrouter.ai/api/v1/chat/completions")));

        Throwable rate = WebClientResponseException.create(
                429,
                "Too Many",
                headers,
                new byte[0],
                StandardCharsets.UTF_8,
                new MockClientHttpRequest(HttpMethod.POST, URI.create("https://openrouter.ai/api/v1/chat/completions")));

        Throwable internal = WebClientResponseException.create(
                500,
                "Server Error",
                headers,
                new byte[0],
                StandardCharsets.UTF_8,
                new MockClientHttpRequest(HttpMethod.POST, URI.create("https://openrouter.ai/api/v1/chat/completions")));

        Throwable badRequest = WebClientResponseException.create(
                400,
                "Bad Request",
                headers,
                new byte[0],
                StandardCharsets.UTF_8,
                new MockClientHttpRequest(HttpMethod.POST, URI.create("https://openrouter.ai/api/v1/chat/completions")));

        AiProviderException authMapped = (AiProviderException) ReflectionTestUtils.invokeMethod(
                client,
                "mapToAiProviderException",
                auth);
        AiProviderException rateMapped = (AiProviderException) ReflectionTestUtils.invokeMethod(
                client,
                "mapToAiProviderException",
                rate);
        AiProviderException internalMapped = (AiProviderException) ReflectionTestUtils.invokeMethod(
                client,
                "mapToAiProviderException",
                internal);
        AiProviderException badRequestMapped = (AiProviderException) ReflectionTestUtils.invokeMethod(
                client,
                "mapToAiProviderException",
                badRequest);

        assertEquals(ErrorCode.OPENROUTER_AUTH, authMapped.getErrorCode());
        assertEquals(ErrorCode.OPENROUTER_RATE_LIMIT, rateMapped.getErrorCode());
        assertEquals(ErrorCode.OPENROUTER_INTERNAL, internalMapped.getErrorCode());
        assertEquals(ErrorCode.OPENROUTER_NETWORK, badRequestMapped.getErrorCode());
    }

    @Test
    void mapsTimeoutAndNetworkToOpenRouterNetwork() {
        OpenRouterClient client = newClient();

        WebClientRequestException networkEx = new WebClientRequestException(
                new IOException("timeout"),
                HttpMethod.POST,
                URI.create("https://openrouter.ai/api/v1/chat/completions"),
                new HttpHeaders());

        AiProviderException networkMapped = (AiProviderException) ReflectionTestUtils.invokeMethod(
                client,
                "mapToAiProviderException",
                networkEx);
        AiProviderException timeoutMapped = (AiProviderException) ReflectionTestUtils.invokeMethod(
                client,
                "mapToAiProviderException",
                new TimeoutException("timeout"));

        assertEquals(ErrorCode.OPENROUTER_NETWORK, networkMapped.getErrorCode());
        assertEquals(ErrorCode.OPENROUTER_NETWORK, timeoutMapped.getErrorCode());
    }

    @Test
    void retryFilterAllowsOnlyTransientErrorCodes() {
        OpenRouterClient client = newClient();

        boolean rateRetryable = (Boolean) ReflectionTestUtils.invokeMethod(
                client,
                "isRetryableThrowable",
                new AiProviderException(ErrorCode.OPENROUTER_RATE_LIMIT, 429, "openrouter.ai", "r", null));

        boolean networkRetryable = (Boolean) ReflectionTestUtils.invokeMethod(
                client,
                "isRetryableThrowable",
                new AiProviderException(ErrorCode.OPENROUTER_NETWORK, 503, "openrouter.ai", "n", null));

        boolean internalRetryable = (Boolean) ReflectionTestUtils.invokeMethod(
                client,
                "isRetryableThrowable",
                new AiProviderException(ErrorCode.OPENROUTER_INTERNAL, 500, "openrouter.ai", "i", null));

        boolean authRetryable = (Boolean) ReflectionTestUtils.invokeMethod(
                client,
                "isRetryableThrowable",
                new AiProviderException(ErrorCode.OPENROUTER_AUTH, 401, "openrouter.ai", "a", null));

        assertTrue(rateRetryable);
        assertTrue(networkRetryable);
        assertTrue(internalRetryable);
        assertFalse(authRetryable);
    }

    private OpenRouterClient newClient() {
        AiRoutingResolver routingResolver = mock(AiRoutingResolver.class);
        when(routingResolver.resolveApiKey()).thenReturn("key");
        return new OpenRouterClient(routingResolver, mock(WebClient.Builder.class), new ObjectMapper());
    }
}
