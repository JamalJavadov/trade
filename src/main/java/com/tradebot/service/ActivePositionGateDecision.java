package com.tradebot.service;

import java.util.LinkedHashMap;
import java.util.Map;

public record ActivePositionGateDecision(
        boolean allowed,
        int activePositionsCount,
        int maxConcurrentPositions,
        int freeSlots,
        String reasonCode,
        String reasonMessage) {

    public Map<String, Object> toPayload() {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("allowed", allowed);
        payload.put("activePositionsCount", activePositionsCount);
        payload.put("maxConcurrentPositions", maxConcurrentPositions);
        payload.put("freeSlots", freeSlots);
        payload.put("reasonCode", reasonCode);
        payload.put("reasonMessage", reasonMessage);
        return payload;
    }
}
