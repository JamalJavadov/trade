package com.tradebot;

import com.tradebot.exception.ApiErrorResponse;
import com.tradebot.exception.GlobalExceptionHandler;
import com.tradebot.trace.TraceIdContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerTest {

    @Test
    void testCatchAllThrowableReturnsJsonAndTraceId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Throwable forcedError = new OutOfMemoryError("Fake OOM");

        ResponseEntity<ApiErrorResponse> result = handler.handleGenericException(forcedError, request);

        assertEquals(500, result.getStatusCode().value());
        assertNotNull(result.getBody());
        assertEquals("INTERNAL", result.getBody().getErrorCode());
        assertNotNull(result.getBody().getTraceId());

        String headerTraceId = response.getHeader(TraceIdContext.TRACE_ID_HEADER);
        assertEquals(result.getBody().getTraceId(), headerTraceId);
    }

    @Test
    void testTransactionExceptionReturnsDbDown503() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/status");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        CannotCreateTransactionException forcedError = new CannotCreateTransactionException("db unavailable");

        ResponseEntity<ApiErrorResponse> result = handler.handleTransactionException(forcedError, request);

        assertEquals(503, result.getStatusCode().value());
        assertNotNull(result.getBody());
        assertEquals("DB_DOWN", result.getBody().getErrorCode());
        assertEquals("/api/v1/status", result.getBody().getPath());
        assertNotNull(result.getBody().getTraceId());

        String headerTraceId = response.getHeader(TraceIdContext.TRACE_ID_HEADER);
        assertEquals(result.getBody().getTraceId(), headerTraceId);
    }

    @Test
    void webClientResponseWith2xxStatusMapsToServiceUnavailable() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/scans/run-once");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        MockClientHttpRequest upstreamRequest = new MockClientHttpRequest(
                HttpMethod.GET,
                URI.create("https://fapi.binance.com/fapi/v1/exchangeInfo"));
        WebClientResponseException exception = WebClientResponseException.create(
                200,
                "OK",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8,
                upstreamRequest);

        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<ApiErrorResponse> result = handler.handleWebClientResponseException(exception, request);

        assertEquals(503, result.getStatusCode().value());
        assertNotNull(result.getBody());
        assertEquals("BINANCE_NETWORK", result.getBody().getErrorCode());
        assertNotNull(result.getBody().getDetails());
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.getBody().getDetails();
        assertEquals(200, details.get("upstreamStatus"));
        assertTrue(details.containsKey("retryHint"));
    }
}
