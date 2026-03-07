package com.tradebot.service.scan;

import java.util.Map;

public record DeterministicEvidence(
        String rawDecision,
        String side,
        String bias,
        String skipReasonCode,
        String skipReasonText,
        boolean valid,
        Map<String, Object> metrics,
        Map<String, Object> diagnostics,
        Map<String, Object> intermediateSignals) {
}
