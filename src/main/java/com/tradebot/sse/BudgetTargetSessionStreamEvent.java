package com.tradebot.sse;

public record BudgetTargetSessionStreamEvent(long eventId, String type, String sessionId, Object payload) {
}
