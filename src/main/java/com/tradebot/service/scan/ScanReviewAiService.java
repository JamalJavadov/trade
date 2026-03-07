package com.tradebot.service.scan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.ai.AiMode;
import com.tradebot.ai.AiRequest;
import com.tradebot.ai.AiRoutingResolver;
import com.tradebot.ai.AiTaskType;
import com.tradebot.ai.ModelRouter;
import com.tradebot.ai.ModelRouterResult;
import com.tradebot.service.AiModelSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScanReviewAiService {

    private static final Set<String> ALLOWED_VERDICTS = Set.of(
            "CONSISTENT",
            "MINOR_CONCERNS",
            "MAJOR_CONTRADICTION",
            "INSUFFICIENT_EVIDENCE");

    private final ModelRouter modelRouter;
    private final AiModelSettingsService aiModelSettingsService;
    private final AiRoutingResolver aiRoutingResolver;
    private final ObjectMapper objectMapper;

    public AiReviewAggregate review(
            FrozenSymbolSnapshot snapshot,
            DeterministicEvidence deterministicEvidence,
            StageAudit integrityAudit,
            StageAudit structuralValidationAudit,
            ConflictReport conflictReport,
            String traceId) {
        if (deterministicEvidence == null || !"VALID".equalsIgnoreCase(deterministicEvidence.rawDecision())) {
            return AiReviewAggregate.skipped("AI scan review is skipped when deterministic evaluation does not produce a valid setup.");
        }
        if (!aiRoutingResolver.isAiEnabled(AiMode.LIVE)) {
            return AiReviewAggregate.unavailable("AI routing is disabled for live scan review.", 8, List.of());
        }

        List<String> modelChain = new ArrayList<>(new LinkedHashSet<>(
                aiModelSettingsService.resolveModelChain(AiMode.LIVE, AiTaskType.SCAN_REVIEW)));
        if (modelChain.isEmpty()) {
            return AiReviewAggregate.unavailable("No allowlisted models are configured for scan review.", 8, List.of());
        }

        List<AiReviewerResult> reviewers = new ArrayList<>();
        AiReviewerResult reviewerA = runReviewer("primary-reviewer", modelChain, snapshot, deterministicEvidence,
                integrityAudit, structuralValidationAudit, conflictReport, traceId);
        reviewers.add(reviewerA);

        List<String> remaining = new ArrayList<>(modelChain);
        if (reviewerA.selectedModel() != null) {
            remaining.removeIf(model -> Objects.equals(model, reviewerA.selectedModel()));
        }
        if (!remaining.isEmpty()) {
            reviewers.add(runReviewer("secondary-reviewer", remaining, snapshot, deterministicEvidence,
                    integrityAudit, structuralValidationAudit, conflictReport, traceId));
        }

        List<AiReviewerResult> successful = reviewers.stream()
                .filter(result -> "COMPLETED".equalsIgnoreCase(result.status()) && result.parseValid())
                .toList();
        if (successful.isEmpty()) {
            return AiReviewAggregate.unavailable("AI scan review was unavailable or returned invalid structured output.", 8, List.copyOf(reviewers));
        }

        String agreementState = successful.size() == 1
                ? "SINGLE_REVIEWER"
                : Objects.equals(successful.get(0).contradictionSeverity(), successful.get(1).contradictionSeverity())
                        && Objects.equals(successful.get(0).trustBlocked(), successful.get(1).trustBlocked())
                ? "AGREE"
                : "DISAGREE";

        boolean majorContradiction = successful.stream()
                .anyMatch(result -> "MAJOR_CONTRADICTION".equalsIgnoreCase(result.contradictionSeverity())
                        || Boolean.TRUE.equals(result.trustBlocked()));
        int confidencePenalty = 0;
        if (successful.size() == 1) {
            confidencePenalty += 5;
        }
        if ("DISAGREE".equalsIgnoreCase(agreementState)) {
            confidencePenalty += 10;
        }
        if (majorContradiction) {
            confidencePenalty += 20;
        }

        String summary = successful.stream()
                .map(AiReviewerResult::summary)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("AI reviewers completed structured scan review.");

        return new AiReviewAggregate(
                "COMPLETED",
                agreementState,
                majorContradiction,
                confidencePenalty,
                summary,
                List.copyOf(reviewers));
    }

    private AiReviewerResult runReviewer(
            String reviewerId,
            List<String> modelChain,
            FrozenSymbolSnapshot snapshot,
            DeterministicEvidence deterministicEvidence,
            StageAudit integrityAudit,
            StageAudit structuralValidationAudit,
            ConflictReport conflictReport,
            String traceId) {
        if (modelChain == null || modelChain.isEmpty()) {
            return new AiReviewerResult(reviewerId, List.of(), null, "SKIPPED", false, true, null,
                    "NONE", null, null, false, "No remaining models for reviewer.", List.of(), List.of(), List.of(),
                    "NO_MODELS", "No remaining allowlisted models.");
        }

        AiRoutingResolver.TaskRoute route = new AiRoutingResolver.TaskRoute(modelChain.get(0),
                modelChain.size() > 1 ? modelChain.subList(1, modelChain.size()) : List.of(),
                true);
        AiRequest request = buildRequest(snapshot, deterministicEvidence, integrityAudit, structuralValidationAudit, conflictReport);
        ModelRouterResult result = modelRouter.runWithFallback(request, route, AiMode.LIVE);
        if (!result.isSuccess() || result.getResponse() == null) {
            return new AiReviewerResult(reviewerId, List.copyOf(modelChain), result.getModelUsed(), "UNAVAILABLE", false,
                    false, result.getLatencyMs(), "NONE", null, null, false,
                    "AI reviewer unavailable.", List.of(), List.of(), List.of(),
                    result.getErrorCode() != null ? result.getErrorCode().name() : "UNAVAILABLE",
                    result.getErrorMessage());
        }

        try {
            ParsedReviewerResponse parsed = parse(result.getResponse().getText());
            return new AiReviewerResult(
                    reviewerId,
                    List.copyOf(modelChain),
                    result.getModelUsed(),
                    "COMPLETED",
                    true,
                    false,
                    result.getLatencyMs(),
                    parsed.contradictionSeverity(),
                    parsed.processConfidence(),
                    parsed.strategyAdherence(),
                    parsed.blockTrust(),
                    parsed.summary(),
                    parsed.contradictions(),
                    parsed.redFlags(),
                    parsed.evidenceGaps(),
                    null,
                    null);
        } catch (Exception ex) {
            log.warn("Scan-review AI response parse failure for {} model={} traceId={}: {}",
                    reviewerId, result.getModelUsed(), traceId, ex.getMessage());
            return new AiReviewerResult(
                    reviewerId,
                    List.copyOf(modelChain),
                    result.getModelUsed(),
                    "INVALID_RESPONSE",
                    false,
                    false,
                    result.getLatencyMs(),
                    "NONE",
                    null,
                    null,
                    false,
                    "AI reviewer returned invalid structured JSON.",
                    List.of(),
                    List.of(),
                    List.of(),
                    "BAD_JSON",
                    ex.getMessage());
        }
    }

    private AiRequest buildRequest(
            FrozenSymbolSnapshot snapshot,
            DeterministicEvidence deterministicEvidence,
            StageAudit integrityAudit,
            StageAudit structuralValidationAudit,
            ConflictReport conflictReport) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("strategyLocks", detail(
                "executionTf", "15m",
                "biasTf", "1h",
                "fractalPeriod", 5,
                "minRrFloor", "2.0",
                "manualPlacementOnly", true));
        payload.put("snapshot", snapshot.toSummary());
        payload.put("deterministicEvidence", deterministicEvidence);
        payload.put("dataIntegrity", integrityAudit);
        payload.put("structuralValidation", structuralValidationAudit);
        payload.put("confirmationConflict", conflictReport);

        String systemPrompt = """
                You are a secondary scan-review auditor for a deterministic trading scanner.
                You must NEVER redefine strategy rules, invent signals, or speculate beyond the supplied evidence.
                Your job is only to verify whether the deterministic analysis appears internally consistent with the fixed strategy evidence.
                Return ONLY valid JSON matching this exact schema:
                {
                  "verdict":"CONSISTENT|MINOR_CONCERNS|MAJOR_CONTRADICTION|INSUFFICIENT_EVIDENCE",
                  "processConfidence":0,
                  "strategyAdherence":0,
                  "blockTrust":false,
                  "summary":"string",
                  "contradictions":["string"],
                  "redFlags":["string"],
                  "evidenceGaps":["string"]
                }
                `blockTrust` may only be true when the supplied evidence itself contains a major contradiction.
                """;

        return AiRequest.builder()
                .taskType(AiTaskType.SCAN_REVIEW)
                .systemPrompt(systemPrompt)
                .userPrompt("Review this deterministic scan evidence JSON and answer with strict JSON only:\n" + toJson(payload))
                .jsonSchemaHint("verdict enum, processConfidence int 0-100, strategyAdherence int 0-100, blockTrust boolean, summary string, contradictions/redFlags/evidenceGaps arrays")
                .temperature(0.0)
                .maxTokens(900)
                .metadata(Map.of("task", AiTaskType.SCAN_REVIEW.name()))
                .build();
    }

    private ParsedReviewerResponse parse(String rawText) throws Exception {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("AI response content was empty.");
        }
        String cleaned = stripCodeFences(rawText);
        JsonNode node = objectMapper.readTree(cleaned);
        String verdict = requiredText(node, "verdict");
        if (!ALLOWED_VERDICTS.contains(verdict)) {
            throw new IllegalArgumentException("Unsupported verdict: " + verdict);
        }
        String summary = requiredText(node, "summary");
        int processConfidence = boundedInt(node.path("processConfidence").asInt(-1), "processConfidence");
        int strategyAdherence = boundedInt(node.path("strategyAdherence").asInt(-1), "strategyAdherence");
        boolean blockTrust = node.path("blockTrust").asBoolean(false);
        List<String> contradictions = readArray(node.path("contradictions"));
        List<String> redFlags = readArray(node.path("redFlags"));
        List<String> evidenceGaps = readArray(node.path("evidenceGaps"));
        return new ParsedReviewerResponse(
                verdict,
                processConfidence,
                strategyAdherence,
                blockTrust,
                summary,
                contradictions,
                redFlags,
                evidenceGaps);
    }

    private String stripCodeFences(String rawText) {
        String cleaned = rawText.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7).trim();
        }
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3).trim();
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
        }
        return cleaned;
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value;
    }

    private int boundedInt(int value, String field) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(field + " must be between 0 and 100");
        }
        return value;
    }

    private List<String> readArray(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        });
        return List.copyOf(values);
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private Map<String, Object> detail(Object... entries) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            detail.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return detail;
    }

    private record ParsedReviewerResponse(
            String contradictionSeverity,
            int processConfidence,
            int strategyAdherence,
            boolean blockTrust,
            String summary,
            List<String> contradictions,
            List<String> redFlags,
            List<String> evidenceGaps) {
    }
}
