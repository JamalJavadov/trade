package com.tradebot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.ai.AiMode;
import com.tradebot.ai.AiRequest;
import com.tradebot.ai.AiRoutingResolver;
import com.tradebot.ai.AiTaskType;
import com.tradebot.ai.ModelRouter;
import com.tradebot.ai.ModelRouterResult;
import com.tradebot.dto.StrategyTuningConfig;
import com.tradebot.entity.AiCallLog;
import com.tradebot.entity.AiProviderAudit;
import com.tradebot.entity.AiSuggestionBatch;
import com.tradebot.entity.AiSuggestionItem;
import com.tradebot.entity.StrategyConfigVersion;
import com.tradebot.entity.TradeExecutionFeedback;
import com.tradebot.exception.AiProviderException;
import com.tradebot.exception.ErrorCode;
import com.tradebot.repository.AiCallLogRepository;
import com.tradebot.repository.AiProviderAuditRepository;
import com.tradebot.repository.AiSuggestionBatchRepository;
import com.tradebot.repository.AiSuggestionItemRepository;
import com.tradebot.repository.StrategyConfigVersionRepository;
import com.tradebot.repository.TradeExecutionFeedbackRepository;
import com.tradebot.trace.TraceIdContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SuggestionBatchService {

    private static final int SAMPLE_TRADES = 10;
    private static final int AUDIT_TEXT_LIMIT = 16_000;
    private static final int STATUS_MAX_LENGTH = 16;
    private static final int SHORT_TEXT_MAX_LENGTH = 64;
    private static final int MODEL_MAX_LENGTH = 255;
    private static final Set<String> RISK_LEVELS = Set.of("low", "medium", "high");

    private final ModelRouter modelRouter;
    private final TradeAnalyticsService analyticsService;
    private final StrategyConfigProvider configProvider;
    private final TradeExecutionFeedbackRepository feedbackRepository;
    private final AiSuggestionBatchRepository batchRepository;
    private final AiSuggestionItemRepository itemRepository;
    private final StrategyConfigVersionRepository configRepository;
    private final AiProviderAuditRepository aiProviderAuditRepository;
    private final AiCallLogRepository aiCallLogRepository;
    private final LiveSuggestionValidationService validationService;
    private final ObjectMapper objectMapper;
    private final AiModelSettingsService aiModelSettingsService;

    @Transactional
    public void generateBatchIfNeeded() {
        long count = feedbackRepository.count();
        if (count > 0 && count % SAMPLE_TRADES == 0) {
            if (batchRepository.countByStatus("PROPOSED") > 0) {
                log.info("A PROPOSED AI batch already exists. Skipping new generation.");
                return;
            }

            log.info("{} trades milestone reached. Generating AI Suggestion Batch...", SAMPLE_TRADES);
            generateBatch();
        }
    }

    private void generateBatch() {
        String traceId = currentTraceId();
        String prompt = null;
        ModelRouterResult routeResult = null;
        List<TradeExecutionFeedback> lastTrades = feedbackRepository.findLastN(SAMPLE_TRADES);

        try {
            TradeAnalyticsService.AnalyticsSnapshot snapshot = analyticsService.getAnalytics(SAMPLE_TRADES);
            StrategyTuningConfig currentConfig = configProvider.getActiveConfig();

            prompt = buildPrompt(snapshot, lastTrades, currentConfig);

            AiRequest aiRequest = AiRequest.builder()
                    .taskType(AiTaskType.SUGGESTION_BATCH)
                    .systemPrompt("Return only strict JSON with no markdown.")
                    .userPrompt(prompt)
                    .metadata(Map.of(
                            "module", "live",
                            "sampleTrades", SAMPLE_TRADES,
                            "taskType", AiTaskType.SUGGESTION_BATCH.name()))
                    .build();

            AiModelSettingsService.ResolvedTaskRoute resolvedRoute = aiModelSettingsService.resolveEffectiveRoute(
                    AiMode.LIVE, AiTaskType.SUGGESTION_BATCH);
            AiRoutingResolver.TaskRoute routingConfig = new AiRoutingResolver.TaskRoute(
                    resolvedRoute.primaryModel(),
                    resolvedRoute.fallbackModels(),
                    true);
            routeResult = modelRouter.runWithFallback(aiRequest, routingConfig, AiMode.LIVE);
            if (routeResult.getTraceId() != null && !routeResult.getTraceId().isBlank()) {
                traceId = routeResult.getTraceId();
            }

            if (!routeResult.isSuccess() || routeResult.getResponse() == null) {
                ErrorCode code = routeResult.getErrorCode() != null
                        ? routeResult.getErrorCode()
                        : ErrorCode.OPENROUTER_NETWORK;
                Integer status = statusForErrorCode(code);
                persistAiAudit(traceId, false, prompt, null, code.name(), status,
                        routeResult.getErrorMessage(), routeResult.getModelUsed());
                persistOutcomeCallLog(traceId, prompt, null, routeResult, false,
                        code.name(), status, routeResult.getErrorMessage());
                persistFailedBatch(traceId, code.name(), routeResult.getErrorMessage(), status,
                        null, routeResult);
                return;
            }

            ParsedAiOutput parsed = parseStrictOutput(routeResult.getResponse().getRawJson());
            String summary = parsed.summary();
            JsonNode itemsArray = parsed.items();

            AiSuggestionBatch batch = new AiSuggestionBatch();
            batch.setCreatedAt(Instant.now());
            batch.setBasedOnLastNTrades(SAMPLE_TRADES);
            batch.setStatus("PROPOSED");
            batch.setSummary(summary);
            batch.setTraceId(traceId);
            batch.setMetaJson(toJson(buildMeta(routeResult)));
            batch.setModel(routeResult.getModelUsed());
            batch.setLatencyMs(routeResult.getLatencyMs());
            batch.setCallStatus("SUCCESS");
            batch = batchRepository.save(batch);

            if (itemsArray.isArray()) {
                for (JsonNode itemNode : itemsArray) {
                    AiSuggestionItem item = new AiSuggestionItem();
                    item.setBatchId(batch.getId());
                    item.setKey(itemNode.path("key").asText());
                    item.setProposedValue(stringifyProposedValue(itemNode.path("proposed_value")));
                    item.setReason(itemNode.path("reason").asText());
                    item.setImpactHypothesis(itemNode.path("impact_hypothesis").asText());
                    item.setRiskOfChange(itemNode.path("risk_of_change").asText());
                    item.setStatus("PROPOSED");
                    itemRepository.save(item);
                }
            }

            persistAiAudit(traceId, true, prompt, routeResult.getResponse().getRawJson(), null, null,
                    null, routeResult.getModelUsed());
            persistOutcomeCallLog(traceId, prompt, routeResult.getResponse().getRawJson(), routeResult, true,
                    null, null, null);
            log.info("AI Suggestion Batch generated successfully. traceId={} model={}",
                    traceId, routeResult.getModelUsed());
        } catch (AiProviderException ex) {
            persistAiAudit(traceId, false, prompt, null, ex.getErrorCode().name(), ex.getHttpStatus(),
                    ex.getMessage(), routeResult != null ? routeResult.getModelUsed() : null);
            persistOutcomeCallLog(traceId, prompt, null, routeResult, false, ex.getErrorCode().name(),
                    ex.getHttpStatus(), ex.getMessage());
            persistFailedBatch(traceId, ex.getErrorCode().name(), ex.getMessage(), ex.getHttpStatus(),
                    ex.getRetryAfter(), routeResult);
            log.warn("AI Suggestion Batch failed with {} traceId={}: {}", ex.getErrorCode(), traceId, ex.getMessage());
        } catch (Exception ex) {
            persistAiAudit(traceId, false, prompt, null, ErrorCode.OPENROUTER_NETWORK.name(), 503,
                    ex.getMessage(), routeResult != null ? routeResult.getModelUsed() : null);
            persistOutcomeCallLog(traceId, prompt, null, routeResult, false, ErrorCode.OPENROUTER_NETWORK.name(),
                    503, ex.getMessage());
            persistFailedBatch(traceId, ErrorCode.OPENROUTER_NETWORK.name(), ex.getMessage(), 503,
                    null, routeResult);
            log.error("Failed to generate AI Suggestion Batch traceId={}", traceId, ex);
        }
    }

    private ParsedAiOutput parseStrictOutput(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (!contentNode.isTextual() || contentNode.asText().isBlank()) {
                throw badJson("OpenRouter returned empty JSON payload");
            }

            JsonNode payload;
            try {
                payload = objectMapper.readTree(contentNode.asText());
            } catch (Exception parseEx) {
                throw badJson("OpenRouter returned invalid JSON payload", parseEx);
            }

            validatePayload(payload);
            return new ParsedAiOutput(payload.path("summary").asText("AI suggestions generated."),
                    payload.path("items"), payload);
        } catch (AiProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw badJson("OpenRouter returned invalid JSON payload", ex);
        }
    }

    private void validatePayload(JsonNode payload) {
        if (payload == null || payload.isMissingNode() || !payload.isObject()) {
            throw badJson("OpenRouter payload must be a JSON object");
        }
        if (!payload.path("summary").isTextual()) {
            throw badJson("OpenRouter payload summary must be a string");
        }

        JsonNode items = payload.path("items");
        if (!items.isArray()) {
            throw badJson("OpenRouter payload items must be an array");
        }

        for (JsonNode item : items) {
            if (!item.isObject()) {
                throw badJson("OpenRouter payload item must be an object");
            }
            String key = requiredText(item, "key");
            if (!item.has("proposed_value") || item.path("proposed_value").isNull()) {
                throw badJson("OpenRouter item proposed_value is missing for key " + key);
            }
            requiredText(item, "reason");
            requiredText(item, "impact_hypothesis");
            String risk = requiredText(item, "risk_of_change").toLowerCase(Locale.ROOT);
            if (!RISK_LEVELS.contains(risk)) {
                throw badJson("OpenRouter item risk_of_change must be one of low|medium|high");
            }
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw badJson("OpenRouter item field is required: " + field);
        }
        return value;
    }

    private AiProviderException badJson(String message) {
        return badJson(message, null);
    }

    private AiProviderException badJson(String message, Throwable cause) {
        return new AiProviderException(ErrorCode.OPENROUTER_BAD_JSON, 422, "openrouter.ai", message, null, cause);
    }

    private void persistFailedBatch(String traceId, String errorCode, String errorMessage, Integer status,
            String retryAfter, ModelRouterResult routeResult) {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("provider", "openrouter.ai");
            details.put("status", status);
            details.put("errorCode", errorCode);
            details.put("traceId", traceId);
            if (retryAfter != null && !retryAfter.isBlank()) {
                details.put("retryAfter", retryAfter);
            }
            details.put("rootMessage", sanitizeText(errorMessage));

            AiSuggestionBatch batch = new AiSuggestionBatch();
            batch.setCreatedAt(Instant.now());
            batch.setBasedOnLastNTrades(SAMPLE_TRADES);
            batch.setStatus("FAILED");
            batch.setSummary("AI suggestion generation failed.");
            batch.setFailedAt(Instant.now());
            batch.setErrorCode(errorCode);
            batch.setErrorMessage(sanitizeText(errorMessage));
            batch.setErrorDetailsJson(toJson(details));
            batch.setErrorJson(toJson(details));
            batch.setMetaJson(toJson(buildMeta(routeResult)));
            batch.setTraceId(traceId);
            if (routeResult != null) {
                batch.setModel(routeResult.getModelUsed());
                batch.setLatencyMs(routeResult.getLatencyMs());
            }
            batch.setCallStatus("FAILED");
            batchRepository.save(batch);
        } catch (Exception e) {
            log.error("Failed to persist FAILED AI batch traceId={}", traceId, e);
        }
    }

    private void persistAiAudit(String traceId, boolean success, String prompt, String response, String errorCode,
            Integer status, String errorMessage, String modelUsed) {
        try {
            AiProviderAudit audit = new AiProviderAudit();
            audit.setProvider("openrouter.ai");
            audit.setModel(sanitizeText(modelUsed != null && !modelUsed.isBlank()
                    ? modelUsed
                    : defaultLiveModel()));
            audit.setPromptText(sanitizeText(prompt));
            audit.setResponseText(sanitizeText(response != null ? response : errorMessage));
            audit.setErrorCode(errorCode);
            audit.setHttpStatus(status);
            audit.setTraceId(traceId);
            audit.setCreatedAt(Instant.now());
            audit.setSuccess(success);
            aiProviderAuditRepository.save(audit);
        } catch (Exception e) {
            log.error("Failed to persist AI provider audit traceId={}", traceId, e);
        }
    }

    private void persistOutcomeCallLog(String traceId, String prompt, String response, ModelRouterResult routeResult,
            boolean success, String errorCode, Integer httpStatus, String errorMessage) {
        try {
            AiCallLog callLog = new AiCallLog();
            callLog.setCreatedAt(Instant.now());
            callLog.setTaskType(truncate(AiTaskType.SUGGESTION_BATCH.name(), SHORT_TEXT_MAX_LENGTH));
            callLog.setProvider(routeResult != null && routeResult.getProvider() != null
                    ? truncate(routeResult.getProvider(), SHORT_TEXT_MAX_LENGTH)
                    : "openrouter.ai");

            String model = routeResult != null && routeResult.getModelUsed() != null
                    ? routeResult.getModelUsed()
                    : defaultLiveModel();
            callLog.setModel(truncate(model, MODEL_MAX_LENGTH));
            callLog.setModelRequested(truncate(model, MODEL_MAX_LENGTH));
            callLog.setModelUsed(truncate(model, MODEL_MAX_LENGTH));
            callLog.setStatus(truncate(success ? "SUCCESS" : "FAILED", STATUS_MAX_LENGTH));
            callLog.setErrorCode(truncate(errorCode, SHORT_TEXT_MAX_LENGTH));
            callLog.setErrorMessage(sanitizeText(errorMessage));
            callLog.setHttpStatus(httpStatus);
            callLog.setLatencyMs(routeResult != null ? routeResult.getLatencyMs() : null);
            callLog.setTraceId(truncate(traceId, SHORT_TEXT_MAX_LENGTH));
            callLog.setPromptText(sanitizeText(prompt));
            callLog.setResponseText(sanitizeText(response != null ? response : errorMessage));
            Map<String, Object> promptJson = new LinkedHashMap<>();
            promptJson.put("prompt", sanitizeText(prompt));
            callLog.setPromptSanitizedJson(toJson(promptJson));

            Map<String, Object> responseJson = new LinkedHashMap<>();
            responseJson.put("response", sanitizeText(response));
            responseJson.put("error", sanitizeText(errorMessage));
            responseJson.put("errorCode", errorCode);
            callLog.setResponseSanitizedJson(toJson(responseJson));
            aiCallLogRepository.save(callLog);
        } catch (Exception e) {
            log.error("Failed to persist ai_call_log traceId={}", traceId, e);
        }
    }

    private Map<String, Object> buildMeta(ModelRouterResult routeResult) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (routeResult == null) {
            return meta;
        }
        meta.put("model_used", routeResult.getModelUsed());
        meta.put("latency_ms", routeResult.getLatencyMs());
        meta.put("task_type", routeResult.getTaskType() != null
                ? routeResult.getTaskType().name()
                : AiTaskType.SUGGESTION_BATCH.name());
        meta.put("provider", routeResult.getProvider() != null ? routeResult.getProvider() : "openrouter.ai");
        meta.put("attempt_count", routeResult.getAttempts() != null ? routeResult.getAttempts().size() : 0);
        return meta;
    }

    private Integer statusForErrorCode(ErrorCode code) {
        if (code == null) {
            return 503;
        }
        return switch (code) {
            case OPENROUTER_AUTH -> 401;
            case OPENROUTER_RATE_LIMIT -> 429;
            case OPENROUTER_BAD_JSON -> 422;
            case OPENROUTER_NETWORK, OPENROUTER_INTERNAL -> 503;
            default -> 503;
        };
    }

    private String stringifyProposedValue(JsonNode valueNode) {
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        if (valueNode.isTextual()) {
            return valueNode.asText();
        }
        return valueNode.toString();
    }

    private String currentTraceId() {
        String traceId = MDC.get(TraceIdContext.TRACE_ID_ATTRIBUTE);
        return traceId != null && !traceId.isBlank() ? traceId : UUID.randomUUID().toString();
    }

    private String sanitizeText(String raw) {
        if (raw == null) {
            return null;
        }
        String masked = raw.replaceAll("sk-[A-Za-z0-9_-]+", "***");
        if (masked.length() > AUDIT_TEXT_LIMIT) {
            return masked.substring(0, AUDIT_TEXT_LIMIT);
        }
        return masked;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String defaultLiveModel() {
        return aiModelSettingsService.resolveEffectiveRoute(AiMode.LIVE, AiTaskType.SUGGESTION_BATCH).primaryModel();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildPrompt(TradeAnalyticsService.AnalyticsSnapshot snapshot, List<TradeExecutionFeedback> trades,
            StrategyTuningConfig config) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert quantitative trading AI focusing on the ICT Smart Money Fibonacci Strategy.\n");
        sb.append("Your goal is to propose precise mathematical parameter tunings to improve strategy performance.\n\n");

        sb.append("HARD INVARIANTS (DO NOT VIOLATE):\n");
        sb.append("- Timeframes are LOCKED to 15m execution and 1h bias.\n");
        sb.append("- minRR MUST remain >= 2.0. Do not suggest anything below 2.0.\n");
        sb.append("- maxEquityPct MUST remain <= 1.0 (hard cap 1%).\n");
        sb.append("- Entry method remains Entry A (market on reclaim close).\n");
        sb.append("- Stop-loss remains beyond sweep wick.\n");
        sb.append("- fractalPeriod MUST remain 5.\n");
        sb.append("- Allowed tuning keys only: ").append(objectMapper.writeValueAsString(validationService.allowedKeys()))
                .append("\n\n");

        sb.append("CURRENT ACTIVE CONFIGURATION:\n");
        sb.append(objectMapper.writeValueAsString(config)).append("\n\n");

        sb.append(String.format("ANALYTICS (Last %d trades):\n", snapshot.totalTrades()));
        sb.append(String.format("- Win Rate: %.2f%%\n", snapshot.winRate() * 100));
        sb.append(String.format("- Avg R Multiple: %.2f\n", snapshot.avgRMultiple()));
        sb.append(String.format("- Expectancy: %.2f\n\n", snapshot.expectancy()));

        sb.append("TRADE SAMPLE (Last 10 trades):\n");
        for (TradeExecutionFeedback f : trades) {
            sb.append(String.format("Label: %s, R: %s, Notes: %s\n",
                    f.getUserLabel(),
                    f.getRMultiple() == null ? "N/A" : f.getRMultiple(),
                    f.getNotes() == null ? "" : f.getNotes()));
        }

        sb.append("\nOutput ONLY valid JSON with no markdown and no extra text, exactly in this schema:\n");
        sb.append("{\n");
        sb.append("  \"summary\": \"...\",\n");
        sb.append("  \"items\": [\n");
        sb.append("    {\n");
        sb.append("      \"key\": \"...\",\n");
        sb.append("      \"proposed_value\": 4,\n");
        sb.append("      \"reason\": \"...\",\n");
        sb.append("      \"impact_hypothesis\": \"...\",\n");
        sb.append("      \"risk_of_change\": \"low|medium|high\"\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n");

        return sb.toString();
    }

    @Transactional
    public void acceptBatch(UUID batchId) throws Exception {
        AiSuggestionBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found"));

        if (!"PROPOSED".equals(batch.getStatus())) {
            throw new IllegalStateException("Batch is not in PROPOSED state");
        }

        List<AiSuggestionItem> items = itemRepository.findByBatchId(batchId);
        if (items.isEmpty()) {
            throw new IllegalStateException("Batch has no suggestion items");
        }

        StrategyTuningConfig config = validationService.applyAcceptedItems(copyConfig(configProvider.getActiveConfig()), items);

        config.getRankingWeights().normalize();

        BigDecimal tpSum = config.getTargets().getTp1Pct().add(config.getTargets().getTp2Pct());
        if (tpSum.compareTo(new BigDecimal("0.90")) > 0) {
            config.getTargets().setTp1Pct(new BigDecimal("0.50"));
            config.getTargets().setTp2Pct(new BigDecimal("0.25"));
        }

        if (config.getRankingWeights().getWeightRR().compareTo(new BigDecimal("0.30")) < 0) {
            config.getRankingWeights().setWeightRR(new BigDecimal("0.30"));
        }

        configRepository.findByActiveTrue().ifPresent(v -> {
            v.setActive(false);
            configRepository.save(v);
        });

        StrategyConfigVersion newVer = new StrategyConfigVersion();
        Integer lastVer = configRepository.findFirstByOrderByVersionDesc()
                .map(StrategyConfigVersion::getVersion)
                .orElse(0);
        newVer.setVersion(lastVer + 1);
        newVer.setCreatedAt(Instant.now());
        newVer.setActive(true);
        newVer.setConfigJson(objectMapper.writeValueAsString(config));
        newVer.setChangeReason("AI Batch " + batchId + " Acceptance");
        configRepository.save(newVer);

        for (AiSuggestionItem item : items) {
            item.setStatus("ACCEPTED");
            itemRepository.save(item);
        }

        batch.setStatus("ACCEPTED");
        batch.setAcceptedAt(Instant.now());
        batchRepository.save(batch);

        configProvider.invalidateCache();
    }

    private StrategyTuningConfig copyConfig(StrategyTuningConfig source) {
        StrategyTuningConfig sourceConfig = source == null ? new StrategyTuningConfig() : source;
        StrategyTuningConfig copy = new StrategyTuningConfig();

        copy.getSweep().setEnterZoneMaxCandles(sourceConfig.getSweep().getEnterZoneMaxCandles());
        copy.getSweep().setInvalidBeyond618Candles(sourceConfig.getSweep().getInvalidBeyond618Candles());

        copy.getBuffers().setSlBufferPct(sourceConfig.getBuffers().getSlBufferPct());

        copy.getFilters().setAtrSpikeFilterEnabled(sourceConfig.getFilters().isAtrSpikeFilterEnabled());
        copy.getFilters().setAtrSpikeMultiplier(sourceConfig.getFilters().getAtrSpikeMultiplier());

        copy.getTargets().setTp2Enabled(sourceConfig.getTargets().isTp2Enabled());
        copy.getTargets().setTp3Enabled(sourceConfig.getTargets().isTp3Enabled());
        copy.getTargets().setTp1Pct(sourceConfig.getTargets().getTp1Pct());
        copy.getTargets().setTp2Pct(sourceConfig.getTargets().getTp2Pct());

        copy.getRankingWeights().setWeightRR(sourceConfig.getRankingWeights().getWeightRR());
        copy.getRankingWeights().setWeightCleanSweep(sourceConfig.getRankingWeights().getWeightCleanSweep());
        copy.getRankingWeights().setWeightReclaimStrength(sourceConfig.getRankingWeights().getWeightReclaimStrength());
        copy.getRankingWeights().setWeightLiquidity(sourceConfig.getRankingWeights().getWeightLiquidity());

        return copy;
    }

    private record ParsedAiOutput(String summary, JsonNode items, JsonNode payload) {
    }
}
