package com.tradebot.service;

import com.tradebot.entity.LiveTradeTriggerMode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public record ApprovedExecutionCommand(
        UUID recommendationId,
        UUID sessionId,
        BigDecimal allocatedBudgetSliceUsdt,
        UUID idempotencyKey,
        LiveTradeTriggerMode triggerMode,
        String operatorId,
        String traceId,
        String operatorNote,
        String requestMessage) {

    public static UUID deterministicKey(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
