package com.tradebot;

import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.dto.RecommendationPlaceabilityDTO;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.entity.Recommendation;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.service.ExchangeExecutionPreflightService;
import com.tradebot.service.LiveTradingBinanceDiagnosticsService;
import com.tradebot.service.LiveTradingBlockerCodes;
import com.tradebot.service.LiveTradingPreflightService;
import com.tradebot.service.RecommendationPlaceabilityService;
import com.tradebot.operator.OperatorPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveTradingPreflightServiceTest {

    private static final List<LiveTradeExecutionState> DUPLICATE_BLOCK_STATES = List.of(
            LiveTradeExecutionState.CREATED,
            LiveTradeExecutionState.PREFLIGHT_VALIDATING,
            LiveTradeExecutionState.ENTRY_SUBMITTING,
            LiveTradeExecutionState.ENTRY_SUBMITTED,
            LiveTradeExecutionState.ENTRY_FILLED,
            LiveTradeExecutionState.PROTECTION_SUBMITTING,
            LiveTradeExecutionState.PROTECTION_ACTIVE,
            LiveTradeExecutionState.ACTIVE,
            LiveTradeExecutionState.CLOSING,
            LiveTradeExecutionState.RECONCILING,
            LiveTradeExecutionState.FAILED);

    private RecommendationRepository recommendationRepository;
    private LiveTradeExecutionRepository executionRepository;
    private RecommendationPlaceabilityService placeabilityService;
    private LiveTradingBinanceDiagnosticsService diagnosticsService;
    private OperatorPermissionService permissionService;
    private ControlCenterSettingsProvider controlCenterSettingsProvider;
    private ExchangeExecutionPreflightService exchangeExecutionPreflightService;

    @BeforeEach
    void setUp() {
        recommendationRepository = mock(RecommendationRepository.class);
        executionRepository = mock(LiveTradeExecutionRepository.class);
        placeabilityService = mock(RecommendationPlaceabilityService.class);
        diagnosticsService = mock(LiveTradingBinanceDiagnosticsService.class);
        permissionService = mock(OperatorPermissionService.class);
        controlCenterSettingsProvider = mock(ControlCenterSettingsProvider.class);
        exchangeExecutionPreflightService = mock(ExchangeExecutionPreflightService.class);

        doAnswer(invocation -> {
            LiveTradingPreflightDTO dto = invocation.getArgument(0);
            ExchangeExecutionPreflightService.ExchangeExecutionPreflightResult result = invocation.getArgument(1);
            dto.getExchangeValidation().setValid(result.valid());
            dto.getExchangeValidation().getFailures().clear();
            dto.getExchangeValidation().getFailures().addAll(result.failures());
            if (result.markPrice() != null) {
                dto.getExchangeValidation().setMarkPrice(result.markPrice());
            }
            if (result.roundedPayloads() != null) {
                dto.getExchangeValidation().setQuantity(result.roundedPayloads().entry().getQuantity());
                dto.getExchangeValidation().setSlStopPrice(result.roundedPayloads().sl().getStopPrice());
                dto.getExchangeValidation().setTpStopPrice(result.roundedPayloads().tp().getStopPrice());
            }
            return null;
        }).when(exchangeExecutionPreflightService).applyTo(any(), any());
    }

    @Test
    void readOnlyModeSurfacesExactRuntimeBlocker() {
        Recommendation recommendation = recommendation();
        when(recommendationRepository.findDetailedById(recommendation.getId())).thenReturn(Optional.of(recommendation));
        when(executionRepository.findFirstByRecommendation_IdAndExecutionStateInOrderByCreatedAtDesc(
                recommendation.getId(),
                DUPLICATE_BLOCK_STATES)).thenReturn(Optional.empty());
        when(placeabilityService.evaluate(recommendation.getId())).thenReturn(placeable());
        when(exchangeExecutionPreflightService.evaluate(eq(recommendation), any()))
                .thenReturn(ExchangeExecutionPreflightService.ExchangeExecutionPreflightResult.invalid(List.of()));
        when(permissionService.isPermissionEnabled("live.execution.enabled")).thenReturn(true);
        when(controlCenterSettingsProvider.isLiveExecutionReadOnly()).thenReturn(true);
        when(diagnosticsService.evaluate("BTCUSDT")).thenReturn(validDiagnostics());

        LiveTradingPreflightService service = service();

        LiveTradingPreflightDTO dto = service.evaluate(recommendation.getId());

        assertFalse(dto.isExecutable());
        assertTrue(dto.getRuntime().isReadOnly());
        assertEquals(LiveTradingBlockerCodes.BOT_READ_ONLY, dto.getBlockedReasons().getFirst().getCode());
    }

    @Test
    void duplicateActiveExecutionSetsRuntimeDuplicateBlocker() {
        Recommendation recommendation = recommendation();
        LiveTradeExecution activeExecution = new LiveTradeExecution();
        activeExecution.setId(UUID.randomUUID());
        activeExecution.setClientRequestId(UUID.randomUUID());
        activeExecution.setExecutionState(LiveTradeExecutionState.ACTIVE);
        activeExecution.setCreatedAt(Instant.now().minusSeconds(15));

        when(recommendationRepository.findDetailedById(recommendation.getId())).thenReturn(Optional.of(recommendation));
        when(executionRepository.findFirstByRecommendation_IdAndExecutionStateInOrderByCreatedAtDesc(
                recommendation.getId(),
                DUPLICATE_BLOCK_STATES)).thenReturn(Optional.of(activeExecution));
        when(placeabilityService.evaluate(recommendation.getId())).thenReturn(placeable());
        when(exchangeExecutionPreflightService.evaluate(eq(recommendation), any()))
                .thenReturn(ExchangeExecutionPreflightService.ExchangeExecutionPreflightResult.invalid(List.of()));
        when(permissionService.isPermissionEnabled("live.execution.enabled")).thenReturn(true);
        when(controlCenterSettingsProvider.isLiveExecutionReadOnly()).thenReturn(false);
        when(diagnosticsService.evaluate("BTCUSDT")).thenReturn(validDiagnostics());

        LiveTradingPreflightDTO dto = service().evaluate(recommendation.getId());

        assertFalse(dto.isExecutable());
        assertTrue(dto.getRuntime().isDuplicateSubmitBlocked());
        assertTrue(dto.getBlockedReasons().stream()
                .anyMatch(reason -> LiveTradingBlockerCodes.DUPLICATE_SUBMIT_BLOCKED.equals(reason.getCode())));
    }

    @Test
    void exchangeFilterFailureBubblesThroughSharedPreflightHelper() {
        Recommendation recommendation = recommendation();
        when(recommendationRepository.findDetailedById(recommendation.getId())).thenReturn(Optional.of(recommendation));
        when(executionRepository.findFirstByRecommendation_IdAndExecutionStateInOrderByCreatedAtDesc(
                recommendation.getId(),
                DUPLICATE_BLOCK_STATES)).thenReturn(Optional.empty());
        when(placeabilityService.evaluate(recommendation.getId())).thenReturn(placeable());
        when(exchangeExecutionPreflightService.evaluate(eq(recommendation), any()))
                .thenReturn(new ExchangeExecutionPreflightService.ExchangeExecutionPreflightResult(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        10,
                        "ISOLATED",
                        "ONE_WAY",
                        List.of("Entry quantity is below Binance minimum quantity.")));
        when(permissionService.isPermissionEnabled("live.execution.enabled")).thenReturn(true);
        when(controlCenterSettingsProvider.isLiveExecutionReadOnly()).thenReturn(false);
        when(diagnosticsService.evaluate("BTCUSDT")).thenReturn(validDiagnostics());

        LiveTradingPreflightDTO dto = service().evaluate(recommendation.getId());

        assertFalse(dto.isExecutable());
        assertFalse(dto.getExchangeValidation().isValid());
        assertEquals("Entry quantity is below Binance minimum quantity.",
                dto.getExchangeValidation().getFailures().getFirst());
        assertEquals(LiveTradingBlockerCodes.EXCHANGE_FILTER_INVALID, dto.getSummary().getPrimaryBlockerCode());
    }

    private LiveTradingPreflightService service() {
        return new LiveTradingPreflightService(
                recommendationRepository,
                executionRepository,
                placeabilityService,
                diagnosticsService,
                permissionService,
                controlCenterSettingsProvider,
                exchangeExecutionPreflightService);
    }

    private Recommendation recommendation() {
        Recommendation recommendation = new Recommendation();
        recommendation.setId(UUID.randomUUID());
        recommendation.setSymbol("BTCUSDT");
        recommendation.setSide("BUY");
        recommendation.setCreatedAt(Instant.now().minusSeconds(60));
        return recommendation;
    }

    private RecommendationPlaceabilityDTO placeable() {
        RecommendationPlaceabilityDTO dto = new RecommendationPlaceabilityDTO();
        dto.setRecommendationId(UUID.randomUUID());
        dto.setSymbol("BTCUSDT");
        dto.setSide("BUY");
        dto.setMarkPrice(new BigDecimal("100000"));
        dto.setManualPlacementAllowed(true);
        dto.setReasonCode("OK");
        dto.setReasonText("placeable");
        return dto;
    }

    private LiveTradingPreflightDTO.Binance validDiagnostics() {
        LiveTradingPreflightDTO.Binance diagnostics = new LiveTradingPreflightDTO.Binance();
        diagnostics.setCredentialsPresent(true);
        diagnostics.setAuthValid(true);
        diagnostics.setFuturesOrderReadOk(true);
        diagnostics.setPositionModeReadOk(true);
        diagnostics.setEndpointFamily("futures");
        diagnostics.setBaseUrl("https://fapi.binance.com");
        diagnostics.setRecvWindowMs(5_000L);
        diagnostics.setRequestIpHint("127.0.0.1");
        diagnostics.setEndpointResults(List.of());
        return diagnostics;
    }
}
