package com.tradebot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.ai.AiMode;
import com.tradebot.ai.AiResponse;
import com.tradebot.ai.AiRoutingResolver;
import com.tradebot.ai.AiTaskType;
import com.tradebot.ai.ModelAttemptResult;
import com.tradebot.ai.ModelRouter;
import com.tradebot.ai.ModelRouterResult;
import com.tradebot.dto.StrategyTuningConfig;
import com.tradebot.entity.AiCallLog;
import com.tradebot.entity.AiSuggestionBatch;
import com.tradebot.entity.AiSuggestionItem;
import com.tradebot.entity.TradeExecutionFeedback;
import com.tradebot.exception.ErrorCode;
import com.tradebot.repository.AiCallLogRepository;
import com.tradebot.repository.AiProviderAuditRepository;
import com.tradebot.repository.AiSuggestionBatchRepository;
import com.tradebot.repository.AiSuggestionItemRepository;
import com.tradebot.repository.StrategyConfigVersionRepository;
import com.tradebot.repository.TradeExecutionFeedbackRepository;
import com.tradebot.service.AiModelSettingsService;
import com.tradebot.service.LiveSuggestionValidationService;
import com.tradebot.service.StrategyConfigProvider;
import com.tradebot.service.SuggestionBatchService;
import com.tradebot.service.TradeAnalyticsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuggestionBatchServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void primaryFailsThenFallbackSucceeds_persistsProposedBatchWithMeta() throws Exception {
        ModelRouter modelRouter = mock(ModelRouter.class);
        TradeAnalyticsService analyticsService = mock(TradeAnalyticsService.class);
        StrategyConfigProvider configProvider = mock(StrategyConfigProvider.class);
        TradeExecutionFeedbackRepository feedbackRepository = mock(TradeExecutionFeedbackRepository.class);
        AiSuggestionBatchRepository batchRepository = mock(AiSuggestionBatchRepository.class);
        AiSuggestionItemRepository itemRepository = mock(AiSuggestionItemRepository.class);
        StrategyConfigVersionRepository configRepository = mock(StrategyConfigVersionRepository.class);
        AiProviderAuditRepository aiProviderAuditRepository = mock(AiProviderAuditRepository.class);
        AiCallLogRepository aiCallLogRepository = mock(AiCallLogRepository.class);

        when(feedbackRepository.count()).thenReturn(10L);
        when(batchRepository.countByStatus("PROPOSED")).thenReturn(0L);
        when(feedbackRepository.findLastN(10)).thenReturn(List.of(new TradeExecutionFeedback()));
        when(analyticsService.getAnalytics(anyInt()))
                .thenReturn(new TradeAnalyticsService.AnalyticsSnapshot(10, 0.5, 1.2, 0.1));
        when(configProvider.getActiveConfig()).thenReturn(new StrategyTuningConfig());
        when(batchRepository.save(any(AiSuggestionBatch.class))).thenAnswer(invocation -> {
            AiSuggestionBatch batch = invocation.getArgument(0);
            if (batch.getId() == null) {
                batch.setId(UUID.randomUUID());
            }
            return batch;
        });

        ModelRouterResult routeResult = ModelRouterResult.builder()
                .success(true)
                .taskType(AiTaskType.SUGGESTION_BATCH)
                .provider("openrouter.ai")
                .modelUsed("fallback-model")
                .latencyMs(245L)
                .traceId("trace-fallback")
                .attempts(List.of(
                        ModelAttemptResult.builder()
                                .modelRequested("primary-model")
                                .modelUsed("primary-model")
                                .errorCode(ErrorCode.OPENROUTER_RATE_LIMIT)
                                .build(),
                        ModelAttemptResult.builder()
                                .modelRequested("fallback-model")
                                .modelUsed("fallback-model")
                                .latencyMs(245L)
                                .build()))
                .response(AiResponse.builder()
                        .modelUsed("fallback-model")
                        .rawJson("""
                                {"choices":[{"message":{"content":"{\\"summary\\":\\"ok\\",\\"items\\":[{\\"key\\":\\"rankingWeights.weightRR\\",\\"proposed_value\\":0.60,\\"reason\\":\\"reason\\",\\"impact_hypothesis\\":\\"impact\\",\\"risk_of_change\\":\\"low\\"}]}"}}]}
                                """)
                        .traceId("trace-fallback")
                        .latencyMs(245L)
                        .build())
                .build();
        when(modelRouter.runWithFallback(any(), any(AiRoutingResolver.TaskRoute.class), eq(AiMode.LIVE)))
                .thenReturn(routeResult);

        SuggestionBatchService service = new SuggestionBatchService(
                modelRouter,
                analyticsService,
                configProvider,
                feedbackRepository,
                batchRepository,
                itemRepository,
                configRepository,
                aiProviderAuditRepository,
                aiCallLogRepository,
                new LiveSuggestionValidationService(objectMapper),
                objectMapper,
                liveRouteSettings());

        service.generateBatchIfNeeded();

        ArgumentCaptor<AiSuggestionBatch> batchCaptor = ArgumentCaptor.forClass(AiSuggestionBatch.class);
        verify(batchRepository).save(batchCaptor.capture());
        AiSuggestionBatch proposed = batchCaptor.getValue();
        assertEquals("PROPOSED", proposed.getStatus());
        assertEquals("trace-fallback", proposed.getTraceId());

        JsonNode metaJson = objectMapper.readTree(proposed.getMetaJson());
        assertEquals("fallback-model", metaJson.path("model_used").asText());
        assertEquals(245L, metaJson.path("latency_ms").asLong());

        verify(itemRepository).save(any(AiSuggestionItem.class));
    }

    @Test
    void invalidJsonClassifiedAsBadJsonAndPersistsFailedRecords() {
        ModelRouter modelRouter = mock(ModelRouter.class);
        TradeAnalyticsService analyticsService = mock(TradeAnalyticsService.class);
        StrategyConfigProvider configProvider = mock(StrategyConfigProvider.class);
        TradeExecutionFeedbackRepository feedbackRepository = mock(TradeExecutionFeedbackRepository.class);
        AiSuggestionBatchRepository batchRepository = mock(AiSuggestionBatchRepository.class);
        AiSuggestionItemRepository itemRepository = mock(AiSuggestionItemRepository.class);
        StrategyConfigVersionRepository configRepository = mock(StrategyConfigVersionRepository.class);
        AiProviderAuditRepository aiProviderAuditRepository = mock(AiProviderAuditRepository.class);
        AiCallLogRepository aiCallLogRepository = mock(AiCallLogRepository.class);

        when(feedbackRepository.count()).thenReturn(10L);
        when(batchRepository.countByStatus("PROPOSED")).thenReturn(0L);
        when(feedbackRepository.findLastN(10)).thenReturn(List.of(new TradeExecutionFeedback()));
        when(analyticsService.getAnalytics(anyInt()))
                .thenReturn(new TradeAnalyticsService.AnalyticsSnapshot(10, 0.5, 1.2, 0.1));
        when(configProvider.getActiveConfig()).thenReturn(new StrategyTuningConfig());

        ModelRouterResult routeResult = ModelRouterResult.builder()
                .success(true)
                .taskType(AiTaskType.SUGGESTION_BATCH)
                .provider("openrouter.ai")
                .modelUsed("primary-model")
                .latencyMs(140L)
                .traceId("trace-bad-json")
                .response(AiResponse.builder()
                        .modelUsed("primary-model")
                        .rawJson("""
                                {"choices":[{"message":{"content":"not-json"}}]}
                                """)
                        .traceId("trace-bad-json")
                        .latencyMs(140L)
                        .build())
                .build();
        when(modelRouter.runWithFallback(any(), any(AiRoutingResolver.TaskRoute.class), eq(AiMode.LIVE)))
                .thenReturn(routeResult);

        SuggestionBatchService service = new SuggestionBatchService(
                modelRouter,
                analyticsService,
                configProvider,
                feedbackRepository,
                batchRepository,
                itemRepository,
                configRepository,
                aiProviderAuditRepository,
                aiCallLogRepository,
                new LiveSuggestionValidationService(objectMapper),
                objectMapper,
                liveRouteSettings());

        assertDoesNotThrow(service::generateBatchIfNeeded);

        ArgumentCaptor<AiSuggestionBatch> batchCaptor = ArgumentCaptor.forClass(AiSuggestionBatch.class);
        verify(batchRepository).save(batchCaptor.capture());
        AiSuggestionBatch failed = batchCaptor.getValue();
        assertEquals("FAILED", failed.getStatus());
        assertEquals(ErrorCode.OPENROUTER_BAD_JSON.name(), failed.getErrorCode());
        assertNotNull(failed.getErrorJson());

        ArgumentCaptor<AiCallLog> callLogCaptor = ArgumentCaptor.forClass(AiCallLog.class);
        verify(aiCallLogRepository).save(callLogCaptor.capture());
        assertEquals("FAILED", callLogCaptor.getValue().getStatus());
        assertEquals(ErrorCode.OPENROUTER_BAD_JSON.name(), callLogCaptor.getValue().getErrorCode());

        verify(itemRepository, never()).save(any(AiSuggestionItem.class));
    }

    @Test
    void acceptBatchRejectsLockedKeyAndDoesNotPersistNewConfigVersion() throws Exception {
        ModelRouter modelRouter = mock(ModelRouter.class);
        TradeAnalyticsService analyticsService = mock(TradeAnalyticsService.class);
        StrategyConfigProvider configProvider = mock(StrategyConfigProvider.class);
        TradeExecutionFeedbackRepository feedbackRepository = mock(TradeExecutionFeedbackRepository.class);
        AiSuggestionBatchRepository batchRepository = mock(AiSuggestionBatchRepository.class);
        AiSuggestionItemRepository itemRepository = mock(AiSuggestionItemRepository.class);
        StrategyConfigVersionRepository configRepository = mock(StrategyConfigVersionRepository.class);
        AiProviderAuditRepository aiProviderAuditRepository = mock(AiProviderAuditRepository.class);
        AiCallLogRepository aiCallLogRepository = mock(AiCallLogRepository.class);

        UUID batchId = UUID.randomUUID();
        AiSuggestionBatch batch = new AiSuggestionBatch();
        batch.setId(batchId);
        batch.setStatus("PROPOSED");
        batch.setCreatedAt(Instant.now());
        when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));

        AiSuggestionItem forbidden = new AiSuggestionItem();
        forbidden.setBatchId(batchId);
        forbidden.setKey("risk.maxEquityPct");
        forbidden.setProposedValue("0.5");
        forbidden.setStatus("PROPOSED");
        when(itemRepository.findByBatchId(batchId)).thenReturn(List.of(forbidden));
        when(configProvider.getActiveConfig()).thenReturn(new StrategyTuningConfig());

        SuggestionBatchService service = new SuggestionBatchService(
                modelRouter,
                analyticsService,
                configProvider,
                feedbackRepository,
                batchRepository,
                itemRepository,
                configRepository,
                aiProviderAuditRepository,
                aiCallLogRepository,
                new LiveSuggestionValidationService(objectMapper),
                objectMapper,
                liveRouteSettings());

        assertThrows(IllegalArgumentException.class, () -> service.acceptBatch(batchId));
        verify(configRepository, never()).save(any());
        verify(itemRepository, never()).save(any(AiSuggestionItem.class));
    }

    private AiModelSettingsService liveRouteSettings() {
        AiModelSettingsService aiModelSettingsService = mock(AiModelSettingsService.class);
        when(aiModelSettingsService.resolveEffectiveRoute(AiMode.LIVE, AiTaskType.SUGGESTION_BATCH))
                .thenReturn(new AiModelSettingsService.ResolvedTaskRoute(
                        AiMode.LIVE,
                        AiTaskType.SUGGESTION_BATCH,
                        "primary-model",
                        List.of("fallback-model")));
        return aiModelSettingsService;
    }
}
