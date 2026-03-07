package com.tradebot.service.scan;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record StageAudit(
        DeepScanStage stage,
        String status,
        List<StageFinding> findings,
        Map<String, Object> details,
        Instant startedAt,
        Instant finishedAt) {

    public boolean failed() {
        return "FAILED".equalsIgnoreCase(status);
    }

    public boolean fragile() {
        return "FRAGILE".equalsIgnoreCase(status);
    }

    public boolean passedOrFragile() {
        return "PASSED".equalsIgnoreCase(status) || fragile();
    }
}
