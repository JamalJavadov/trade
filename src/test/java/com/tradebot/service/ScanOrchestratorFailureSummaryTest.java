package com.tradebot.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferLimitException;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanOrchestratorFailureSummaryTest {

    @Test
    void summarizeFailureIncludesRootCauseDetails() {
        Throwable root = new DataBufferLimitException("Exceeded limit on max bytes to buffer : 262144");
        Throwable top = new IllegalStateException("200 OK from GET https://fapi.binance.com/fapi/v1/exchangeInfo",
                root);

        String summary = ScanOrchestrator.summarizeFailure(top);

        assertTrue(summary.contains("200 OK from GET https://fapi.binance.com/fapi/v1/exchangeInfo"));
        assertTrue(summary.contains("rootCause=DataBufferLimitException"));
        assertTrue(summary.contains("262144"));
    }
}
