package com.tradebot.service.scan;

import com.tradebot.config.AppProperties;
import com.tradebot.dto.StrategyTuningConfig;
import com.tradebot.service.RecommendationPlaceabilityMath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DeepScanPipelineService {

    private static final int FINAL_INTEGRITY_THRESHOLD = 80;

    private final DataIntegrityValidationService dataIntegrityValidationService;
    private final DeterministicStrategyEvaluator deterministicStrategyEvaluator;
    private final DeepStructuralValidationService deepStructuralValidationService;
    private final SecondPassConfirmationService secondPassConfirmationService;
    private final ScanReviewAiService scanReviewAiService;

    public DeepScanCandidateResult evaluate(
            FrozenSymbolSnapshot snapshot,
            AppProperties runtimeProperties,
            StrategyTuningConfig tuningConfig,
            ScanCandidateEventService.Recorder recorder) {

        recorder.record(DeepScanStage.DATA_INTEGRITY, "STARTED", detail("symbol", snapshot.symbol()));
        StageAudit integrityAudit = dataIntegrityValidationService.evaluate(snapshot);
        recorder.record(DeepScanStage.DATA_INTEGRITY, integrityAudit.status(), detail(
                "status", integrityAudit.status(),
                "findingCount", integrityAudit.findings().size(),
                "details", integrityAudit.details()));

        DeterministicStrategyResult deterministicResult;
        if (integrityAudit.failed()) {
            deterministicResult = new DeterministicStrategyResult(
                    "NO_TRADE",
                    "NONE",
                    null,
                    "DATA_ERROR",
                    "Critical data-integrity failure blocked deterministic strategy execution.",
                    false,
                    null,
                    null,
                    null,
                    null,
                    Map.of(),
                    Map.of("integrityBlocked", true),
                    Map.of("dataIntegrityStatus", integrityAudit.status()));
        } else {
            recorder.record(DeepScanStage.DETERMINISTIC_STRATEGY, "STARTED", detail("symbol", snapshot.symbol()));
            deterministicResult = deterministicStrategyEvaluator.evaluate(snapshot, runtimeProperties, tuningConfig);
        }
        recorder.record(DeepScanStage.DETERMINISTIC_STRATEGY,
                deterministicResult.valid() ? "PASSED" : "FAILED",
                detail(
                        "decision", deterministicResult.decision(),
                        "side", deterministicResult.side(),
                        "bias", deterministicResult.bias(),
                        "skipReasonCode", deterministicResult.skipReasonCode(),
                        "metrics", deterministicResult.metrics()));
        DeterministicEvidence deterministicEvidence = deterministicResult.toEvidence();

        recorder.record(DeepScanStage.STRUCTURAL_VALIDATION, "STARTED", detail("decision", deterministicResult.decision()));
        StageAudit structuralValidation = deepStructuralValidationService.evaluate(snapshot, deterministicResult);
        recorder.record(DeepScanStage.STRUCTURAL_VALIDATION, structuralValidation.status(), detail(
                "findingCount", structuralValidation.findings().size(),
                "details", structuralValidation.details()));

        recorder.record(DeepScanStage.SECOND_PASS_CONFIRMATION, "STARTED", detail("decision", deterministicResult.decision()));
        SecondPassConfirmationService.ConfirmationResult confirmation = secondPassConfirmationService.evaluate(
                snapshot,
                runtimeProperties,
                tuningConfig,
                deterministicResult,
                deterministicStrategyEvaluator);
        recorder.record(DeepScanStage.SECOND_PASS_CONFIRMATION, confirmation.audit().status(), detail(
                "conflictState", confirmation.report().state(),
                "blocking", confirmation.report().blocking(),
                "disagreements", confirmation.report().disagreements()));

        recorder.record(DeepScanStage.AI_COMPARATIVE_REVIEW, "STARTED", detail("decision", deterministicResult.decision()));
        AiReviewAggregate aiReview = scanReviewAiService.review(
                snapshot,
                deterministicEvidence,
                integrityAudit,
                structuralValidation,
                confirmation.report(),
                snapshot.traceId());
        recorder.record(DeepScanStage.AI_COMPARATIVE_REVIEW, aiReview.status(), detail(
                "agreementState", aiReview.agreementState(),
                "majorContradiction", aiReview.majorContradiction(),
                "confidencePenalty", aiReview.confidencePenalty(),
                "reviewerCount", aiReview.reviewers().size()));

        recorder.record(DeepScanStage.FINAL_GATE, "STARTED", detail("symbol", snapshot.symbol()));
        RecommendationGateResult gate = finalGate(
                snapshot,
                runtimeProperties,
                deterministicResult,
                integrityAudit,
                structuralValidation,
                confirmation.report(),
                aiReview);
        recorder.record(DeepScanStage.FINAL_GATE, gate.eligible() ? "ELIGIBLE" : "BLOCKED", detail(
                "finalIntegrityScore", gate.finalIntegrityScore(),
                "eligible", gate.eligible(),
                "rejectionReasons", gate.rejectionReasons(),
                "details", gate.details()));

        return new DeepScanCandidateResult(
                snapshot.scanRunId(),
                snapshot.symbol(),
                snapshot.traceId(),
                snapshot.toSummary(),
                integrityAudit,
                deterministicEvidence,
                structuralValidation,
                confirmation.audit(),
                confirmation.report(),
                aiReview,
                gate,
                Instant.now());
    }

    private RecommendationGateResult finalGate(
            FrozenSymbolSnapshot snapshot,
            AppProperties runtimeProperties,
            DeterministicStrategyResult deterministicResult,
            StageAudit integrityAudit,
            StageAudit structuralValidation,
            ConflictReport conflictReport,
            AiReviewAggregate aiReview) {
        List<String> rejectionReasons = new ArrayList<>();
        int integrityScore = 100;

        boolean deterministicValid = deterministicResult != null && deterministicResult.valid();
        if (!deterministicValid) {
            integrityScore -= 60;
            rejectionReasons.add(deterministicResult != null && deterministicResult.skipReasonCode() != null
                    ? deterministicResult.skipReasonCode()
                    : "DETERMINISTIC_INVALID");
        }

        boolean dataIntegrityPassed = !integrityAudit.failed();
        if (!dataIntegrityPassed) {
            integrityScore -= 40;
            rejectionReasons.add("DATA_INTEGRITY_FAILED");
        } else if (integrityAudit.fragile()) {
            integrityScore -= 10;
        }

        boolean structuralValidationPassed = !structuralValidation.failed();
        if (!structuralValidationPassed) {
            integrityScore -= 25;
            rejectionReasons.add("STRUCTURAL_VALIDATION_FAILED");
        } else if (structuralValidation.fragile()) {
            integrityScore -= 12;
        }

        boolean confirmationPassed = !conflictReport.blocking();
        if (!confirmationPassed) {
            integrityScore -= 30;
            rejectionReasons.add(conflictReport.state());
        } else if (!"NONE".equalsIgnoreCase(conflictReport.state())) {
            integrityScore -= 12;
            rejectionReasons.add(conflictReport.state());
        }

        boolean placeabilityRealismOk = staticPlaceabilityRealismOk(deterministicResult, runtimeProperties, snapshot);
        if (!placeabilityRealismOk) {
            integrityScore -= 15;
            rejectionReasons.add("PLACEABILITY_REALISM_FAILED");
        }

        integrityScore = Math.max(0, integrityScore - aiReview.confidencePenalty());
        boolean aiBlocked = aiReview.majorContradiction()
                && (!dataIntegrityPassed || !structuralValidationPassed || !confirmationPassed || !"NONE".equalsIgnoreCase(conflictReport.state()));
        if (aiBlocked) {
            rejectionReasons.add("AI_MAJOR_CONTRADICTION_CORROBORATED");
        }

        boolean eligible = deterministicValid
                && dataIntegrityPassed
                && structuralValidationPassed
                && confirmationPassed
                && placeabilityRealismOk
                && integrityScore >= FINAL_INTEGRITY_THRESHOLD
                && !aiBlocked;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("threshold", FINAL_INTEGRITY_THRESHOLD);
        details.put("aiStatus", aiReview.status());
        details.put("aiAgreementState", aiReview.agreementState());
        details.put("conflictState", conflictReport.state());
        details.put("dataIntegrityStatus", integrityAudit.status());
        details.put("structuralValidationStatus", structuralValidation.status());
        details.put("deterministicDecision", deterministicResult != null ? deterministicResult.decision() : null);
        details.put("placeabilityRealismOk", placeabilityRealismOk);

        return new RecommendationGateResult(
                eligible,
                integrityScore,
                placeabilityRealismOk,
                aiBlocked,
                dataIntegrityPassed,
                structuralValidationPassed,
                confirmationPassed,
                deterministicValid,
                List.copyOf(rejectionReasons),
                immutable(details));
    }

    private boolean staticPlaceabilityRealismOk(
            DeterministicStrategyResult deterministicResult,
            AppProperties runtimeProperties,
            FrozenSymbolSnapshot snapshot) {
        if (deterministicResult == null || deterministicResult.executionPlan() == null) {
            return false;
        }
        var plan = deterministicResult.executionPlan();
        java.math.BigDecimal tickSize = null;
        try {
            tickSize = new java.math.BigDecimal(String.valueOf(snapshot.exchangeMetadata().get("tickSize")));
        } catch (Exception ignored) {
            tickSize = null;
        }
        if (tickSize == null || tickSize.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return false;
        }
        try {
            return RecommendationPlaceabilityMath.evaluate(
                    deterministicResult.side(),
                    plan.entryPrice(),
                    tickSize,
                    plan.tp1Price(),
                    plan.slPrice(),
                    runtimeProperties.getStrategy().getMinRr()).placeable();
        } catch (Exception ex) {
            return false;
        }
    }

    private Map<String, Object> detail(Object... entries) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            detail.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return Collections.unmodifiableMap(detail);
    }

    private Map<String, Object> immutable(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
