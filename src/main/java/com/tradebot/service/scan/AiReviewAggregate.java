package com.tradebot.service.scan;

import java.util.List;

public record AiReviewAggregate(
        String status,
        String agreementState,
        boolean majorContradiction,
        int confidencePenalty,
        String summary,
        List<AiReviewerResult> reviewers) {

    public static AiReviewAggregate skipped(String summary) {
        return new AiReviewAggregate("SKIPPED", "NONE", false, 0, summary, List.of());
    }

    public static AiReviewAggregate unavailable(String summary, int confidencePenalty, List<AiReviewerResult> reviewers) {
        return new AiReviewAggregate("UNAVAILABLE", "NONE", false, confidencePenalty, summary, reviewers);
    }
}
