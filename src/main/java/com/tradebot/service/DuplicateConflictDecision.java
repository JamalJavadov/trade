package com.tradebot.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record DuplicateConflictDecision(
        boolean allowed,
        String symbol,
        UUID conflictingExecutionId,
        String conflictingExecutionState,
        String reasonCode,
        String reasonMessage) {

    public Map<String, Object> toPayload() {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("allowed", allowed);
        payload.put("symbol", symbol);
        payload.put("conflictingExecutionId", conflictingExecutionId);
        payload.put("conflictingExecutionState", conflictingExecutionState);
        payload.put("reasonCode", reasonCode);
        payload.put("reasonMessage", reasonMessage);
        return payload;
    }
}
