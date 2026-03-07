package com.tradebot.service.scan;

import java.util.List;

public record AiReviewerResult(
        String reviewerId,
        List<String> requestedModels,
        String selectedModel,
        String status,
        boolean parseValid,
        boolean skipped,
        Long latencyMs,
        String contradictionSeverity,
        Integer processConfidence,
        Integer strategyAdherence,
        Boolean trustBlocked,
        String summary,
        List<String> contradictions,
        List<String> redFlags,
        List<String> evidenceGaps,
        String failureCode,
        String failureDetail) {
}
