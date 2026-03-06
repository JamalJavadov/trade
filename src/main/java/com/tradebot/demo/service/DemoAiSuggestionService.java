package com.tradebot.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.ai.AiMode;
import com.tradebot.ai.AiRequest;
import com.tradebot.ai.AiRoutingResolver;
import com.tradebot.ai.AiTaskType;
import com.tradebot.ai.ModelRouter;
import com.tradebot.ai.ModelRouterResult;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.demo.dto.DemoAiLatestResponseDTO;
import com.tradebot.demo.dto.DemoAiModelsStatusResponseDTO;
import com.tradebot.demo.dto.DemoTuningConfig;
import com.tradebot.demo.entity.DemoAiCallLog;
import com.tradebot.demo.entity.DemoAiSuggestionBatch;
import com.tradebot.demo.entity.DemoAiSuggestionItem;
import com.tradebot.demo.entity.DemoStrategyConfigVersion;
import com.tradebot.demo.entity.DemoTrade;
import com.tradebot.demo.repository.DemoAiCallLogRepository;
import com.tradebot.demo.repository.DemoAiSuggestionBatchRepository;
import com.tradebot.demo.repository.DemoAiSuggestionItemRepository;
import com.tradebot.demo.repository.DemoTradeRepository;
import com.tradebot.exception.AiProviderException;
import com.tradebot.exception.ErrorCode;
import com.tradebot.service.AiModelSettingsService;
import com.tradebot.trace.TraceIdContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DemoAiSuggestionService {

    private static final int DEMO_AI_EVERY_N_TRADES = 10;

    private final ControlCenterSettingsProvider controlCenterSettingsProvider;
    private final DemoTradeRepository demoTradeRepository;
    private final DemoAiCallLogRepository demoAiCallLogRepository;
    private final DemoAiSuggestionBatchRepository batchRepository;
    private final DemoAiSuggestionItemRepository itemRepository;
    private final DemoStrategyConfigProvider configProvider;
    private final DemoStrategyConfigService configService;
    private final DemoSuggestionValidationService validationService;
    private final DemoAnalyticsService analyticsService;
    private final ModelRouter modelRouter;
    private final AiRoutingResolver routingResolver;
    private final ObjectMapper objectMapper;
    private final AiModelSettingsService aiModelSettingsService;

    @Transactional(readOnly = true)
    public DemoAiLatestResponseDTO getLatestProposedWithConfig() {
        DemoAiSuggestionBatch batch = batchRepository.findFirstByStatusOrderByCreatedAtDesc("PROPOSED").orElse(null);
        List<DemoAiSuggestionItem> items = batch != null ? itemRepository.findByBatchId(batch.getId()) : List.of();
        DemoStrategyConfigVersion activeConfig = configProvider.getActiveConfigVersion();

        DemoAiLatestResponseDTO dto = new DemoAiLatestResponseDTO();
        dto.setBatch(toBatchDto(batch));
        dto.setItems(items.stream().map(this::toItemDto).toList());
        dto.setActiveConfigVersion(toConfigDto(activeConfig));
        return dto;
    }

    @Transactional(readOnly = true)
    public DemoAiModelsStatusResponseDTO getModelsStatus() {
        DemoAiModelsStatusResponseDTO dto = new DemoAiModelsStatusResponseDTO();
        dto.setEnabled(routingResolver.isAiEnabled(AiMode.DEMO));

        Map<String, List<String>> routing = new LinkedHashMap<>();
        for (AiTaskType taskType : AiTaskType.values()) {
            routing.put(taskType.name(), routingResolver.resolveTaskRoute(taskType, AiMode.DEMO).modelChain());
        }
        dto.setRouting(routing);

        DemoAiCallLog lastCall = demoAiCallLogRepository
                .findFirstByTaskTypeOrderByCreatedAtDesc(AiTaskType.SUGGESTION_BATCH.name())
                .orElse(null);
        dto.setLastCall(toLastCallDto(lastCall));
        return dto;
    }

    @Transactional
    public void acceptBatch(UUID batchId) {
        acceptBatch(batchId, "local-operator");
    }

    @Transactional
    public void acceptBatch(UUID batchId, String acceptedBy) {
        DemoAiSuggestionBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("Demo AI batch not found: " + batchId));
        if (!"PROPOSED".equals(batch.getStatus())) {
            throw new IllegalStateException("Demo AI batch is not in PROPOSED state");
        }

        List<DemoAiSuggestionItem> items = itemRepository.findByBatchId(batchId);
        if (items.isEmpty()) {
            throw new IllegalStateException("Demo AI batch has no suggestion items");
        }

        configService.createNewVersionFromAcceptedItems(batchId, batch.getSummary(), items);

        for (DemoAiSuggestionItem item : items) {
            item.setStatus("ACCEPTED");
            itemRepository.save(item);
        }

        batch.setStatus("ACCEPTED");
        batch.setAcceptedAt(Instant.now());
        batch.setAcceptedBy(acceptedBy == null || acceptedBy.isBlank() ? "local-operator" : acceptedBy.trim());
        batchRepository.save(batch);
    }

    @Transactional
    public void rejectBatch(UUID batchId) {
        DemoAiSuggestionBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("Demo AI batch not found: " + batchId));
        if (!"PROPOSED".equals(batch.getStatus())) {
            throw new IllegalStateException("Demo AI batch is not in PROPOSED state");
        }

        List<DemoAiSuggestionItem> items = itemRepository.findByBatchId(batchId);
        for (DemoAiSuggestionItem item : items) {
            item.setStatus("REJECTED");
            itemRepository.save(item);
        }

        batch.setStatus("REJECTED");
        batch.setRejectedAt(Instant.now());
        batchRepository.save(batch);
    }

    @Transactional(noRollbackFor = AiProviderException.class)
    public void generateBatchNow(boolean demoOnly) {
        if (!demoOnly) {
            throw new IllegalArgumentException("generate-now requires demoOnly=true");
        }
        if (batchRepository.countByStatus("PROPOSED") > 0) {
            return;
        }
        generateBatch(DEMO_AI_EVERY_N_TRADES, true);
    }

    @Transactional
    public void generateBatchIfNeeded() {
        var demoConfig = controlCenterSettingsProvider.getConfigSnapshot().getDemoTrading();
        if (!controlCenterSettingsProvider.isAiEnabled(AiMode.DEMO) || !demoConfig.isEnabled()) {
            return;
        }

        int n = DEMO_AI_EVERY_N_TRADES;
        long closedTrades = demoTradeRepository.countByStatus("CLOSED");
        if (closedTrades < n || closedTrades % n != 0) {
            return;
        }

        if (batchRepository.countByStatus("PROPOSED") > 0) {
            return;
        }

        generateBatch(n, false);
    }

    private void generateBatch(int n, boolean rethrowFailures) {
        String traceId = currentTraceId();

        List<DemoTrade> trades = demoTradeRepository.findLastClosed(n);
        DemoTuningConfig activeConfig = configProvider.getActiveConfig();
        DemoAnalyticsService.AnalyticsResult analytics10 = analyticsService.getSummary(10);
        DemoAnalyticsService.AnalyticsResult analytics50 = analyticsService.getSummary(50);

        Map<String, Object> promptPayload = buildPromptPayload(trades, activeConfig, analytics10, analytics50, n);
        String prompt = buildPrompt(promptPayload);

        DemoAiSuggestionBatch batch = new DemoAiSuggestionBatch();
        batch.setCreatedAt(Instant.now());
        batch.setBasedOnLastNTrades(n);
        batch.setTraceId(traceId);

        AiRequest aiRequest = AiRequest.builder()
                .taskType(AiTaskType.SUGGESTION_BATCH)
                .systemPrompt("You are optimizing DEMO paper-trading tuning only. Return strict JSON output.")
                .userPrompt(prompt)
                .metadata(Map.of(
                        "module", "demo-trading",
                        "basedOnLastNTrades", n))
                .build();

        AiModelSettingsService.ResolvedTaskRoute resolvedRoute = aiModelSettingsService.resolveEffectiveRoute(
                AiMode.DEMO, AiTaskType.SUGGESTION_BATCH);
        AiRoutingResolver.TaskRoute routingConfig = new AiRoutingResolver.TaskRoute(
                resolvedRoute.primaryModel(),
                resolvedRoute.fallbackModels(),
                true);
        ModelRouterResult routeResult = modelRouter.runWithFallback(aiRequest, routingConfig, AiMode.DEMO);
        traceId = routeResult.getTraceId() != null ? routeResult.getTraceId() : traceId;
        batch.setTraceId(traceId);

        if (!routeResult.isSuccess() || routeResult.getResponse() == null) {
            String code = routeResult.getErrorCode() != null
                    ? routeResult.getErrorCode().name()
                    : ErrorCode.OPENROUTER_INTERNAL.name();
            String failedModel = null;
            Long failedLatencyMs = null;
            if (routeResult.getAttempts() != null && !routeResult.getAttempts().isEmpty()) {
                var lastAttempt = routeResult.getAttempts().get(routeResult.getAttempts().size() - 1);
                failedModel = lastAttempt.getModelUsed() != null
                        ? lastAttempt.getModelUsed()
                        : lastAttempt.getModelRequested();
                failedLatencyMs = lastAttempt.getLatencyMs();
            }
            failBatch(batch, promptPayload, code, routeResult.getErrorMessage(), traceId, failedModel, failedLatencyMs);
            if (rethrowFailures) {
                throw new AiProviderException(
                        routeResult.getErrorCode() != null ? routeResult.getErrorCode() : ErrorCode.OPENROUTER_INTERNAL,
                        503,
                        "openrouter.ai",
                        routeResult.getErrorMessage() != null ? routeResult.getErrorMessage() : "All models failed",
                        null);
            }
            return;
        }

        try {
            ParsedAiOutput parsed = parseAiOutput(routeResult.getResponse().getRawJson());

            batch.setStatus("PROPOSED");
            batch.setModel(routeResult.getResponse().getModelUsed());
            batch.setLatencyMs(routeResult.getResponse().getLatencyMs());
            batch.setCallStatus("SUCCESS");
            batch.setSummary(parsed.summary());
            batch.setPromptJson(toJson(promptPayload));
            batch.setResponseJson(toJson(parsed.rawResponseJson()));
            batch = batchRepository.save(batch);

            for (JsonNode itemNode : parsed.items()) {
                DemoAiSuggestionItem item = new DemoAiSuggestionItem();
                item.setBatchId(batch.getId());
                item.setKey(itemNode.path("key").asText());
                item.setProposedValue(itemNode.path("proposed_value").toString());
                item.setReason(itemNode.path("reason").asText());
                item.setImpactHypothesis(itemNode.path("impact_hypothesis").asText());
                item.setRiskOfChange(itemNode.path("risk_of_change").asText("medium"));
                item.setStatus("PROPOSED");
                itemRepository.save(item);
            }
        } catch (AiProviderException ex) {
            failBatch(
                    batch,
                    promptPayload,
                    ex.getErrorCode().name(),
                    ex.getMessage(),
                    traceId,
                    routeResult.getResponse().getModelUsed(),
                    routeResult.getResponse().getLatencyMs());
            if (rethrowFailures) {
                throw ex;
            }
        } catch (Exception ex) {
            failBatch(
                    batch,
                    promptPayload,
                    ErrorCode.OPENROUTER_BAD_JSON.name(),
                    ex.getMessage(),
                    traceId,
                    routeResult.getResponse().getModelUsed(),
                    routeResult.getResponse().getLatencyMs());
            if (rethrowFailures) {
                throw new AiProviderException(
                        ErrorCode.OPENROUTER_BAD_JSON,
                        422,
                        "openrouter.ai",
                        "OpenRouter returned invalid JSON payload",
                        null,
                        ex);
            }
        }
    }

    private void failBatch(
            DemoAiSuggestionBatch batch,
            Map<String, Object> promptPayload,
            String errorCode,
            String message,
            String traceId,
            String model,
            Long latencyMs) {
        log.warn("Demo AI suggestion generation failed [{}]: {}", errorCode, message);
        batch.setStatus("FAILED");
        batch.setSummary("Demo AI generation failed");
        batch.setPromptJson(toJson(promptPayload));
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("errorCode", errorCode);
        error.put("message", sanitizeText(message));
        error.put("traceId", traceId);
        batch.setErrorJson(toJson(error));
        batch.setFailedAt(Instant.now());
        batch.setErrorCode(errorCode);
        batch.setTraceId(traceId);
        batch.setModel(model);
        batch.setLatencyMs(latencyMs);
        batch.setCallStatus("FAILED");
        batchRepository.save(batch);
    }

    private ParsedAiOutput parseAiOutput(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            String content = contentNode.asText(null);
            if (content == null || content.isBlank()) {
                throw new AiProviderException(ErrorCode.OPENROUTER_BAD_JSON, 422, "openrouter.ai",
                        "OpenRouter returned empty JSON payload", null);
            }

            String cleaned = content.replace("```json", "").replace("```", "").trim();
            JsonNode payload = objectMapper.readTree(cleaned);
            JsonNode items = payload.path("items");
            if (!items.isArray()) {
                throw new AiProviderException(ErrorCode.OPENROUTER_BAD_JSON, 422, "openrouter.ai",
                        "OpenRouter items field is not an array", null);
            }
            if (items.size() > 5) {
                throw new AiProviderException(ErrorCode.OPENROUTER_BAD_JSON, 422, "openrouter.ai",
                        "OpenRouter returned more than 5 items", null);
            }

            List<JsonNode> validatedItems = new ArrayList<>();
            for (JsonNode item : items) {
                validationService.validateAiItem(item);
                validatedItems.add(item);
            }

            String summary = payload.path("summary").asText("Demo AI suggestions generated");
            JsonNode itemArray = objectMapper.valueToTree(validatedItems);
            return new ParsedAiOutput(summary, itemArray, payload);
        } catch (AiProviderException ex) {
            throw ex;
        } catch (Exception e) {
            throw new AiProviderException(
                    ErrorCode.OPENROUTER_BAD_JSON,
                    422,
                    "openrouter.ai",
                    "OpenRouter returned invalid JSON payload",
                    null,
                    e);
        }
    }

    private Map<String, Object> buildPromptPayload(
            List<DemoTrade> trades,
            DemoTuningConfig activeConfig,
            DemoAnalyticsService.AnalyticsResult analytics10,
            DemoAnalyticsService.AnalyticsResult analytics50,
            int sampleSize) {

        List<Map<String, Object>> sample = new ArrayList<>();
        for (DemoTrade trade : trades) {
            JsonNode snapshot = parseJson(trade.getSnapshotJson());
            JsonNode diagnostics = snapshot.path("diagnostics");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", trade.getSymbol());
            row.put("side", trade.getSide());
            row.put("opened_at", trade.getOpenedAt());
            row.put("close_reason", trade.getCloseReason());
            row.put("pnl_usdt", trade.getPnlUsdt());
            row.put("r_multiple", trade.getRMultiple());
            row.put("sweepDepthBucket", bucketByRatio(decimalOrDefault(diagnostics.path("sweepDepthRatio"), BigDecimal.ZERO)));
            row.put("reclaimStrengthBucket", bucketByRatio(decimalOrDefault(diagnostics.path("reclaimStrength"), BigDecimal.ZERO)));
            row.put("atrBucket", bucketAtr(decimalOrDefault(diagnostics.path("atrRatio"), BigDecimal.ONE)));
            row.put("rrLiveAtEntry", decimalOrDefault(snapshot.path("rrLive"), DemoCandidateEvaluator.LOCKED_MIN_RR));
            row.put("staleSetup", diagnostics.path("staleSetup").asBoolean(false));
            row.put("timeStopHit", "TIME_STOP".equalsIgnoreCase(trade.getCloseReason()));
            sample.add(row);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("strategy", "DEMO_SM_FIB_SWEEP_CONTINUATION");
        payload.put("sampleSize", sampleSize);
        payload.put("activeConfig", activeConfig);
        payload.put("invariants", List.of(
                "minRR >= 2.0 (locked)",
                "risk cap <= 1% equity (locked)",
                "Entry A only: market on reclaim close proxy (locked)",
                "SL beyond sweep wick only (locked, do not tighten into golden zone)",
                "executionTf=15m, biasTf=1h, fractalPeriod=5 (locked)"));
        payload.put("analyticsLast10", analytics10);
        payload.put("analyticsLast50", analytics50);
        payload.put("lastClosedTrades", sample);
        payload.put("allowedKeys", validationService.allowedKeys());
        return payload;
    }

    private String buildPrompt(Map<String, Object> payload) {
        return """
                You are optimizing DEMO paper-trading tuning only.
                Return ONLY valid JSON and no text outside JSON.
                Do NOT include any keys outside allowedKeys.
                Never change locked invariants.
                Max 5 items.
                Output schema:
                {
                  "summary":"string",
                  "items":[
                    {
                      "key":"string",
                      "proposed_value":<number|boolean|integer>,
                      "reason":"string",
                      "impact_hypothesis":"string",
                      "risk_of_change":"low|medium|high"
                    }
                  ]
                }
                Input:
                """ + sanitizeText(toJson(payload));
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private BigDecimal decimalOrDefault(JsonNode node, BigDecimal fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        try {
            if (node.isNumber()) {
                return node.decimalValue();
            }
            if (node.isTextual()) {
                return new BigDecimal(node.asText());
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private String bucketByRatio(BigDecimal value) {
        if (value.compareTo(new BigDecimal("0.33")) < 0) {
            return "weak";
        }
        if (value.compareTo(new BigDecimal("0.66")) < 0) {
            return "medium";
        }
        return "strong";
    }

    private String bucketAtr(BigDecimal value) {
        if (value.compareTo(new BigDecimal("0.9")) < 0) {
            return "low";
        }
        if (value.compareTo(new BigDecimal("1.3")) > 0) {
            return "high";
        }
        return "med";
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
        int limit = Math.max(1, routingResolver.safety().getMaxInputChars());
        if (masked.length() > limit) {
            return masked.substring(0, limit);
        }
        return masked;
    }

    private DemoAiModelsStatusResponseDTO.LastCallDTO toLastCallDto(DemoAiCallLog callLog) {
        if (callLog == null) {
            return null;
        }
        DemoAiModelsStatusResponseDTO.LastCallDTO dto = new DemoAiModelsStatusResponseDTO.LastCallDTO();
        dto.setStatus(callLog.getStatus());
        dto.setModelUsed(callLog.getModelUsed());
        dto.setErrorCode(callLog.getErrorCode());
        dto.setTraceId(callLog.getTraceId());
        dto.setLatencyMs(callLog.getLatencyMs());
        return dto;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private DemoAiLatestResponseDTO.BatchDTO toBatchDto(DemoAiSuggestionBatch batch) {
        if (batch == null) {
            return null;
        }
        DemoAiLatestResponseDTO.BatchDTO dto = new DemoAiLatestResponseDTO.BatchDTO();
        dto.setId(batch.getId());
        dto.setCreatedAt(batch.getCreatedAt());
        dto.setBasedOnLastNTrades(batch.getBasedOnLastNTrades());
        dto.setStatus(batch.getStatus());
        dto.setSummary(batch.getSummary());
        dto.setModel(batch.getModel());
        dto.setPromptJson(batch.getPromptJson());
        dto.setResponseJson(batch.getResponseJson());
        dto.setErrorJson(batch.getErrorJson());
        dto.setAcceptedAt(batch.getAcceptedAt());
        dto.setAcceptedBy(batch.getAcceptedBy());
        dto.setRejectedAt(batch.getRejectedAt());
        dto.setRejectReason(batch.getRejectReason());
        dto.setFailedAt(batch.getFailedAt());
        dto.setErrorCode(batch.getErrorCode());
        dto.setTraceId(batch.getTraceId());
        dto.setLatencyMs(batch.getLatencyMs());
        dto.setCallStatus(batch.getCallStatus());
        return dto;
    }

    private DemoAiLatestResponseDTO.ItemDTO toItemDto(DemoAiSuggestionItem item) {
        DemoAiLatestResponseDTO.ItemDTO dto = new DemoAiLatestResponseDTO.ItemDTO();
        dto.setBatchId(item.getBatchId());
        dto.setKey(item.getKey());
        dto.setProposedValue(item.getProposedValue());
        dto.setReason(item.getReason());
        dto.setImpactHypothesis(item.getImpactHypothesis());
        dto.setRiskOfChange(item.getRiskOfChange());
        dto.setStatus(item.getStatus());
        return dto;
    }

    private DemoAiLatestResponseDTO.ConfigVersionDTO toConfigDto(DemoStrategyConfigVersion version) {
        DemoAiLatestResponseDTO.ConfigVersionDTO dto = new DemoAiLatestResponseDTO.ConfigVersionDTO();
        dto.setId(version.getId());
        dto.setVersion(version.getVersion());
        dto.setCreatedAt(version.getCreatedAt());
        dto.setActive(version.getActive());
        dto.setConfigJson(version.getConfigJson());
        dto.setChangeReason(version.getChangeReason());
        return dto;
    }

    private record ParsedAiOutput(String summary, JsonNode items, JsonNode rawResponseJson) {
    }
}
