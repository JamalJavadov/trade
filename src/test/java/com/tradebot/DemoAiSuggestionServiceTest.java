package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.ai.AiMode;
import com.tradebot.ai.AiRequest;
import com.tradebot.ai.AiResponse;
import com.tradebot.ai.AiRoutingResolver;
import com.tradebot.ai.AiTaskType;
import com.tradebot.ai.ModelRouter;
import com.tradebot.ai.ModelRouterResult;
import com.tradebot.config.AiProperties;
import com.tradebot.config.DemoTradingProperties;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.demo.dto.DemoTuningConfig;
import com.tradebot.demo.entity.DemoAiSuggestionBatch;
import com.tradebot.demo.entity.DemoAiSuggestionItem;
import com.tradebot.demo.entity.DemoStrategyConfigVersion;
import com.tradebot.demo.repository.DemoAiCallLogRepository;
import com.tradebot.demo.repository.DemoAiSuggestionBatchRepository;
import com.tradebot.demo.repository.DemoAiSuggestionItemRepository;
import com.tradebot.demo.repository.DemoTradeRepository;
import com.tradebot.demo.service.DemoAiSuggestionService;
import com.tradebot.demo.service.DemoAnalyticsService;
import com.tradebot.demo.service.DemoStrategyConfigProvider;
import com.tradebot.demo.service.DemoStrategyConfigService;
import com.tradebot.demo.service.DemoSuggestionValidationService;
import com.tradebot.exception.AiProviderException;
import com.tradebot.exception.ErrorCode;
import com.tradebot.service.AiModelSettingsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoAiSuggestionServiceTest {

    @Test
    void generateBatchNoOpsUntilThreshold() {
        DemoTradingProperties properties = new DemoTradingProperties();
        properties.setAiEnabled(true);
        properties.setAiEveryNTrades(10);

        DemoTradeRepository tradeRepository = mock(DemoTradeRepository.class);
        when(tradeRepository.countByStatus("CLOSED")).thenReturn(9L);

        DemoAiSuggestionBatchRepository batchRepository = mock(DemoAiSuggestionBatchRepository.class);
        DemoAiSuggestionItemRepository itemRepository = mock(DemoAiSuggestionItemRepository.class);
        DemoAiCallLogRepository callLogRepository = mock(DemoAiCallLogRepository.class);
        DemoStrategyConfigProvider configProvider = mock(DemoStrategyConfigProvider.class);
        DemoStrategyConfigService configService = mock(DemoStrategyConfigService.class);
        DemoAnalyticsService analyticsService = mock(DemoAnalyticsService.class);
        ModelRouter modelRouter = mock(ModelRouter.class);
        AiRoutingResolver routingResolver = routingResolverMock();

        DemoAiSuggestionService service = newService(
                properties,
                tradeRepository,
                callLogRepository,
                batchRepository,
                itemRepository,
                configProvider,
                configService,
                analyticsService,
                modelRouter,
                routingResolver);

        service.generateBatchIfNeeded();

        verify(modelRouter, never()).runWithFallback(any(AiRequest.class), any(AiRoutingResolver.TaskRoute.class), eq(AiMode.DEMO));
    }

    @Test
    void rejectBatchUpdatesBatchAndItems() {
        DemoTradingProperties properties = new DemoTradingProperties();

        DemoTradeRepository tradeRepository = mock(DemoTradeRepository.class);
        DemoAiSuggestionBatchRepository batchRepository = mock(DemoAiSuggestionBatchRepository.class);
        DemoAiSuggestionItemRepository itemRepository = mock(DemoAiSuggestionItemRepository.class);
        DemoAiCallLogRepository callLogRepository = mock(DemoAiCallLogRepository.class);
        DemoStrategyConfigProvider configProvider = mock(DemoStrategyConfigProvider.class);
        DemoStrategyConfigService configService = mock(DemoStrategyConfigService.class);
        DemoAnalyticsService analyticsService = mock(DemoAnalyticsService.class);
        ModelRouter modelRouter = mock(ModelRouter.class);
        AiRoutingResolver routingResolver = routingResolverMock();

        DemoAiSuggestionService service = newService(
                properties,
                tradeRepository,
                callLogRepository,
                batchRepository,
                itemRepository,
                configProvider,
                configService,
                analyticsService,
                modelRouter,
                routingResolver);

        UUID batchId = UUID.randomUUID();
        DemoAiSuggestionBatch batch = new DemoAiSuggestionBatch();
        batch.setId(batchId);
        batch.setStatus("PROPOSED");

        DemoAiSuggestionItem item = new DemoAiSuggestionItem();
        item.setBatchId(batchId);
        item.setKey("management.timeStopMinutes");
        item.setStatus("PROPOSED");

        when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(itemRepository.findByBatchId(batchId)).thenReturn(List.of(item));

        service.rejectBatch(batchId);

        verify(batchRepository).save(any(DemoAiSuggestionBatch.class));
        verify(itemRepository).save(any(DemoAiSuggestionItem.class));
        assertEquals("REJECTED", batch.getStatus());
        assertEquals("REJECTED", item.getStatus());
    }

    @Test
    void acceptBatchTriggersConfigVersioning() {
        DemoTradingProperties properties = new DemoTradingProperties();

        DemoTradeRepository tradeRepository = mock(DemoTradeRepository.class);
        DemoAiSuggestionBatchRepository batchRepository = mock(DemoAiSuggestionBatchRepository.class);
        DemoAiSuggestionItemRepository itemRepository = mock(DemoAiSuggestionItemRepository.class);
        DemoAiCallLogRepository callLogRepository = mock(DemoAiCallLogRepository.class);
        DemoStrategyConfigProvider configProvider = mock(DemoStrategyConfigProvider.class);
        DemoStrategyConfigService configService = mock(DemoStrategyConfigService.class);
        DemoAnalyticsService analyticsService = mock(DemoAnalyticsService.class);
        ModelRouter modelRouter = mock(ModelRouter.class);
        AiRoutingResolver routingResolver = routingResolverMock();

        DemoAiSuggestionService service = newService(
                properties,
                tradeRepository,
                callLogRepository,
                batchRepository,
                itemRepository,
                configProvider,
                configService,
                analyticsService,
                modelRouter,
                routingResolver);

        UUID batchId = UUID.randomUUID();
        DemoAiSuggestionBatch batch = new DemoAiSuggestionBatch();
        batch.setId(batchId);
        batch.setStatus("PROPOSED");
        batch.setSummary("summary");

        DemoAiSuggestionItem item = new DemoAiSuggestionItem();
        item.setBatchId(batchId);
        item.setKey("sweep.maxSweepCandles");
        item.setProposedValue("4");

        when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(itemRepository.findByBatchId(batchId)).thenReturn(List.of(item));
        when(configService.createNewVersionFromAcceptedItems(batchId, "summary", List.of(item)))
                .thenReturn(new DemoStrategyConfigVersion());

        service.acceptBatch(batchId, "operator-1");

        verify(configService).createNewVersionFromAcceptedItems(batchId, "summary", List.of(item));
        verify(batchRepository).save(any(DemoAiSuggestionBatch.class));
        verify(itemRepository).save(any(DemoAiSuggestionItem.class));
        assertEquals("ACCEPTED", batch.getStatus());
        assertEquals("operator-1", batch.getAcceptedBy());
    }

    @Test
    void generateBatchBadJsonMarksFailed() {
        DemoTradingProperties properties = new DemoTradingProperties();
        properties.setAiEnabled(true);
        properties.setAiEveryNTrades(10);

        DemoTradeRepository tradeRepository = mock(DemoTradeRepository.class);
        when(tradeRepository.countByStatus("CLOSED")).thenReturn(10L);
        when(tradeRepository.findLastClosed(10)).thenReturn(List.of());

        DemoAiSuggestionBatchRepository batchRepository = mock(DemoAiSuggestionBatchRepository.class);
        when(batchRepository.countByStatus("PROPOSED")).thenReturn(0L);
        when(batchRepository.save(any(DemoAiSuggestionBatch.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DemoAiSuggestionItemRepository itemRepository = mock(DemoAiSuggestionItemRepository.class);
        DemoAiCallLogRepository callLogRepository = mock(DemoAiCallLogRepository.class);
        DemoStrategyConfigProvider configProvider = mock(DemoStrategyConfigProvider.class);
        when(configProvider.getActiveConfig()).thenReturn(new DemoTuningConfig());
        DemoStrategyConfigVersion active = activeVersion();
        when(configProvider.getActiveConfigVersion()).thenReturn(active);

        DemoStrategyConfigService configService = mock(DemoStrategyConfigService.class);
        DemoAnalyticsService analyticsService = analyticsServiceMock();

        ModelRouter modelRouter = mock(ModelRouter.class);
        when(modelRouter.runWithFallback(any(AiRequest.class), any(AiRoutingResolver.TaskRoute.class), eq(AiMode.DEMO)))
                .thenReturn(successResult("{\"choices\":[{\"message\":{\"content\":\"not-json\"}}]}", "m1", "trace-1"));

        DemoAiSuggestionService service = newService(
                properties,
                tradeRepository,
                callLogRepository,
                batchRepository,
                itemRepository,
                configProvider,
                configService,
                analyticsService,
                modelRouter,
                routingResolverMock());

        service.generateBatchIfNeeded();

        verify(batchRepository).save(any(DemoAiSuggestionBatch.class));
        verify(itemRepository, never()).save(any(DemoAiSuggestionItem.class));
    }

    @Test
    void generateBatchRouterFailureDoesNotThrowForLifecycle() {
        DemoTradingProperties properties = new DemoTradingProperties();
        properties.setAiEnabled(true);
        properties.setAiEveryNTrades(10);

        DemoTradeRepository tradeRepository = mock(DemoTradeRepository.class);
        when(tradeRepository.countByStatus("CLOSED")).thenReturn(10L);
        when(tradeRepository.findLastClosed(10)).thenReturn(List.of());

        DemoAiSuggestionBatchRepository batchRepository = mock(DemoAiSuggestionBatchRepository.class);
        when(batchRepository.countByStatus("PROPOSED")).thenReturn(0L);
        when(batchRepository.save(any(DemoAiSuggestionBatch.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DemoAiSuggestionItemRepository itemRepository = mock(DemoAiSuggestionItemRepository.class);
        DemoAiCallLogRepository callLogRepository = mock(DemoAiCallLogRepository.class);
        DemoStrategyConfigProvider configProvider = mock(DemoStrategyConfigProvider.class);
        when(configProvider.getActiveConfig()).thenReturn(new DemoTuningConfig());
        when(configProvider.getActiveConfigVersion()).thenReturn(activeVersion());

        DemoStrategyConfigService configService = mock(DemoStrategyConfigService.class);
        DemoAnalyticsService analyticsService = analyticsServiceMock();

        ModelRouter modelRouter = mock(ModelRouter.class);
        when(modelRouter.runWithFallback(any(AiRequest.class), any(AiRoutingResolver.TaskRoute.class), eq(AiMode.DEMO)))
                .thenReturn(ModelRouterResult.builder()
                        .success(false)
                        .errorCode(ErrorCode.OPENROUTER_RATE_LIMIT)
                        .errorMessage("rate limited")
                        .traceId("trace-rate-limit")
                        .build());

        DemoAiSuggestionService service = newService(
                properties,
                tradeRepository,
                callLogRepository,
                batchRepository,
                itemRepository,
                configProvider,
                configService,
                analyticsService,
                modelRouter,
                routingResolverMock());

        service.generateBatchIfNeeded();

        ArgumentCaptor<DemoAiSuggestionBatch> batchCaptor = ArgumentCaptor.forClass(DemoAiSuggestionBatch.class);
        verify(batchRepository).save(batchCaptor.capture());
        assertEquals("FAILED", batchCaptor.getValue().getStatus());
        assertEquals("FAILED", batchCaptor.getValue().getCallStatus());
        assertEquals("OPENROUTER_RATE_LIMIT", batchCaptor.getValue().getErrorCode());
        verify(itemRepository, never()).save(any(DemoAiSuggestionItem.class));
    }

    @Test
    void generateBatchNowRethrowsWhenRouterFails() {
        DemoTradingProperties properties = new DemoTradingProperties();
        properties.setAiEnabled(true);
        properties.setAiEveryNTrades(10);

        DemoTradeRepository tradeRepository = mock(DemoTradeRepository.class);
        when(tradeRepository.findLastClosed(10)).thenReturn(List.of());

        DemoAiSuggestionBatchRepository batchRepository = mock(DemoAiSuggestionBatchRepository.class);
        when(batchRepository.countByStatus("PROPOSED")).thenReturn(0L);
        when(batchRepository.save(any(DemoAiSuggestionBatch.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DemoAiSuggestionItemRepository itemRepository = mock(DemoAiSuggestionItemRepository.class);
        DemoAiCallLogRepository callLogRepository = mock(DemoAiCallLogRepository.class);
        DemoStrategyConfigProvider configProvider = mock(DemoStrategyConfigProvider.class);
        when(configProvider.getActiveConfig()).thenReturn(new DemoTuningConfig());
        when(configProvider.getActiveConfigVersion()).thenReturn(activeVersion());

        DemoStrategyConfigService configService = mock(DemoStrategyConfigService.class);
        DemoAnalyticsService analyticsService = analyticsServiceMock();

        ModelRouter modelRouter = mock(ModelRouter.class);
        when(modelRouter.runWithFallback(any(AiRequest.class), any(AiRoutingResolver.TaskRoute.class), eq(AiMode.DEMO)))
                .thenReturn(ModelRouterResult.builder()
                        .success(false)
                        .errorCode(ErrorCode.OPENROUTER_INTERNAL)
                        .errorMessage("all models failed")
                        .traceId("trace-fail")
                        .build());

        DemoAiSuggestionService service = newService(
                properties,
                tradeRepository,
                callLogRepository,
                batchRepository,
                itemRepository,
                configProvider,
                configService,
                analyticsService,
                modelRouter,
                routingResolverMock());

        assertThrows(AiProviderException.class, () -> service.generateBatchNow(true));
    }

    @Test
    void generateBatchSkipsWhenProposedAlreadyExists() {
        DemoTradingProperties properties = new DemoTradingProperties();
        properties.setAiEnabled(true);
        properties.setAiEveryNTrades(10);

        DemoTradeRepository tradeRepository = mock(DemoTradeRepository.class);
        when(tradeRepository.countByStatus("CLOSED")).thenReturn(10L);

        DemoAiSuggestionBatchRepository batchRepository = mock(DemoAiSuggestionBatchRepository.class);
        when(batchRepository.countByStatus("PROPOSED")).thenReturn(1L);

        DemoAiSuggestionItemRepository itemRepository = mock(DemoAiSuggestionItemRepository.class);
        DemoAiCallLogRepository callLogRepository = mock(DemoAiCallLogRepository.class);
        DemoStrategyConfigProvider configProvider = mock(DemoStrategyConfigProvider.class);
        DemoStrategyConfigService configService = mock(DemoStrategyConfigService.class);
        DemoAnalyticsService analyticsService = mock(DemoAnalyticsService.class);
        ModelRouter modelRouter = mock(ModelRouter.class);

        DemoAiSuggestionService service = newService(
                properties,
                tradeRepository,
                callLogRepository,
                batchRepository,
                itemRepository,
                configProvider,
                configService,
                analyticsService,
                modelRouter,
                routingResolverMock());

        service.generateBatchIfNeeded();
        verify(modelRouter, never()).runWithFallback(any(AiRequest.class), any(AiRoutingResolver.TaskRoute.class), eq(AiMode.DEMO));
    }

    @Test
    void generateBatchValidJsonPersistsItems() {
        DemoTradingProperties properties = new DemoTradingProperties();
        properties.setAiEnabled(true);
        properties.setAiEveryNTrades(10);

        DemoTradeRepository tradeRepository = mock(DemoTradeRepository.class);
        when(tradeRepository.countByStatus("CLOSED")).thenReturn(10L);
        when(tradeRepository.findLastClosed(10)).thenReturn(List.of());

        DemoAiSuggestionBatchRepository batchRepository = mock(DemoAiSuggestionBatchRepository.class);
        when(batchRepository.countByStatus("PROPOSED")).thenReturn(0L);
        when(batchRepository.save(any(DemoAiSuggestionBatch.class))).thenAnswer(invocation -> {
            DemoAiSuggestionBatch b = invocation.getArgument(0);
            if (b.getId() == null) {
                b.setId(UUID.randomUUID());
            }
            return b;
        });

        DemoAiSuggestionItemRepository itemRepository = mock(DemoAiSuggestionItemRepository.class);
        DemoAiCallLogRepository callLogRepository = mock(DemoAiCallLogRepository.class);
        DemoStrategyConfigProvider configProvider = mock(DemoStrategyConfigProvider.class);
        when(configProvider.getActiveConfig()).thenReturn(new DemoTuningConfig());
        when(configProvider.getActiveConfigVersion()).thenReturn(activeVersion());

        DemoStrategyConfigService configService = mock(DemoStrategyConfigService.class);
        DemoAnalyticsService analyticsService = analyticsServiceMock();

        ModelRouter modelRouter = mock(ModelRouter.class);
        when(modelRouter.runWithFallback(any(AiRequest.class), any(AiRoutingResolver.TaskRoute.class), eq(AiMode.DEMO)))
                .thenReturn(successResult(
                        """
                                {"choices":[{"message":{"content":"{\\"summary\\":\\"ok\\",\\"items\\":[{\\"key\\":\\"rankingWeights.weightRR\\",\\"proposed_value\\":0.60,\\"reason\\":\\"r\\",\\"impact_hypothesis\\":\\"i\\",\\"risk_of_change\\":\\"low\\"}]}"}}]}
                                """,
                        "router-model",
                        "trace-ok"));

        DemoAiSuggestionService service = newService(
                properties,
                tradeRepository,
                callLogRepository,
                batchRepository,
                itemRepository,
                configProvider,
                configService,
                analyticsService,
                modelRouter,
                routingResolverMock());

        service.generateBatchIfNeeded();
        verify(itemRepository).save(any(DemoAiSuggestionItem.class));

        ArgumentCaptor<DemoAiSuggestionBatch> batchCaptor = ArgumentCaptor.forClass(DemoAiSuggestionBatch.class);
        verify(batchRepository).save(batchCaptor.capture());
        DemoAiSuggestionBatch persisted = batchCaptor.getValue();
        assertEquals("SUCCESS", persisted.getCallStatus());
        assertEquals("router-model", persisted.getModel());
    }

    private DemoAiSuggestionService newService(
            DemoTradingProperties properties,
            DemoTradeRepository tradeRepository,
            DemoAiCallLogRepository callLogRepository,
            DemoAiSuggestionBatchRepository batchRepository,
            DemoAiSuggestionItemRepository itemRepository,
            DemoStrategyConfigProvider configProvider,
            DemoStrategyConfigService configService,
            DemoAnalyticsService analyticsService,
            ModelRouter modelRouter,
            AiRoutingResolver routingResolver) {
        ObjectMapper objectMapper = new ObjectMapper();
        DemoSuggestionValidationService validation = new DemoSuggestionValidationService(objectMapper);
        AiModelSettingsService aiModelSettingsService = mock(AiModelSettingsService.class);
        ControlCenterSettingsProvider controlCenterSettingsProvider = mock(ControlCenterSettingsProvider.class);
        ControlCenterConfig controlConfig = new ControlCenterConfig();
        controlConfig.getDemoTrading().setAiEnabled(properties.isAiEnabled());
        controlConfig.getDemoTrading().setAiEveryNTrades(properties.getAiEveryNTrades());
        when(controlCenterSettingsProvider.getConfigSnapshot()).thenReturn(controlConfig);
        when(aiModelSettingsService.resolveEffectiveRoute(AiMode.DEMO, AiTaskType.SUGGESTION_BATCH))
                .thenReturn(new AiModelSettingsService.ResolvedTaskRoute(
                        AiMode.DEMO,
                        AiTaskType.SUGGESTION_BATCH,
                        properties.getAiModel(),
                        List.of()));
        return new DemoAiSuggestionService(
                controlCenterSettingsProvider,
                tradeRepository,
                callLogRepository,
                batchRepository,
                itemRepository,
                configProvider,
                configService,
                validation,
                analyticsService,
                modelRouter,
                routingResolver,
                objectMapper,
                aiModelSettingsService);
    }

    private AiRoutingResolver routingResolverMock() {
        AiRoutingResolver routingResolver = mock(AiRoutingResolver.class);
        when(routingResolver.safety()).thenReturn(new AiProperties.Safety());
        when(routingResolver.isAiEnabled(AiMode.DEMO)).thenReturn(true);
        return routingResolver;
    }

    private DemoAnalyticsService analyticsServiceMock() {
        DemoAnalyticsService analyticsService = mock(DemoAnalyticsService.class);
        when(analyticsService.getSummary(10)).thenReturn(new DemoAnalyticsService.AnalyticsResult(
                10, Instant.now(), Map.of(), Map.of(), List.of()));
        when(analyticsService.getSummary(50)).thenReturn(new DemoAnalyticsService.AnalyticsResult(
                50, Instant.now(), Map.of(), Map.of(), List.of()));
        return analyticsService;
    }

    private DemoStrategyConfigVersion activeVersion() {
        DemoStrategyConfigVersion active = new DemoStrategyConfigVersion();
        active.setId(UUID.randomUUID());
        active.setVersion(1);
        active.setCreatedAt(Instant.now());
        active.setActive(true);
        return active;
    }

    private ModelRouterResult successResult(String rawJson, String modelUsed, String traceId) {
        return ModelRouterResult.builder()
                .success(true)
                .traceId(traceId)
                .response(AiResponse.builder()
                        .rawJson(rawJson)
                        .modelUsed(modelUsed)
                        .traceId(traceId)
                        .build())
                .build();
    }
}
