package com.tradebot.sse;

public record ScanEvent(long eventId, String type, String scanRunId, Object payload) {
}
