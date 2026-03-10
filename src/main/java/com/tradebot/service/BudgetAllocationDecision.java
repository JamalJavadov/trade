package com.tradebot.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public record BudgetAllocationDecision(
        boolean allowed,
        BigDecimal sessionBudgetUsdt,
        BigDecimal realizedNetPnlUsdt,
        BigDecimal unrealizedNetPnlUsdt,
        BigDecimal reservedActiveExposureUsdt,
        BigDecimal spendableBudgetUsdt,
        BigDecimal allocatedSliceUsdt,
        int activePositionsCount,
        int freeSlots,
        String reasonCode,
        String reasonMessage) {

    public Map<String, Object> toPayload() {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("allowed", allowed);
        payload.put("sessionBudgetUsdt", sessionBudgetUsdt);
        payload.put("realizedNetPnlUsdt", realizedNetPnlUsdt);
        payload.put("unrealizedNetPnlUsdt", unrealizedNetPnlUsdt);
        payload.put("reservedActiveExposureUsdt", reservedActiveExposureUsdt);
        payload.put("spendableBudgetUsdt", spendableBudgetUsdt);
        payload.put("allocatedSliceUsdt", allocatedSliceUsdt);
        payload.put("activePositionsCount", activePositionsCount);
        payload.put("freeSlots", freeSlots);
        payload.put("reasonCode", reasonCode);
        payload.put("reasonMessage", reasonMessage);
        return payload;
    }
}
