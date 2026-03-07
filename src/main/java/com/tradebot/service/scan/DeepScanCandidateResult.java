package com.tradebot.service.scan;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DeepScanCandidateResult(
        UUID scanRunId,
        String symbol,
        String traceId,
        Map<String, Object> snapshotSummary,
        StageAudit dataIntegrity,
        DeterministicEvidence deterministicEvidence,
        StageAudit structuralValidation,
        StageAudit secondPassConfirmation,
        ConflictReport conflictReport,
        AiReviewAggregate aiReview,
        RecommendationGateResult finalGate,
        Instant completedAt) {
}
