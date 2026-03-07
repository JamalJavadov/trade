package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
import com.tradebot.config.AppProperties;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.dto.BinanceExchangeInfoResponse;
import com.tradebot.dto.RecommendationPlaceabilityDTO;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionState;
import com.tradebot.entity.OrderFields;
import com.tradebot.entity.Recommendation;
import com.tradebot.entity.ScanRun;
import com.tradebot.operator.OperatorPermissionService;
import com.tradebot.repository.LiveTradeExecutionRepository;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.service.LiveTradingBinanceDiagnosticsService;
import com.tradebot.service.LiveTradingBlockerCodes;
import com.tradebot.service.LiveTradingPreflightService;
import com.tradebot.service.RecommendationPlaceabilityService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveTradingPreflightServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readOnlyModeSurfacesExactRuntimeBlocker() throws Exception {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        RecommendationPlaceabilityService placeabilityService = mock(RecommendationPlaceabilityService.class);
        BinanceClient binanceClient = mock(BinanceClient.class);
        LiveTradingBinanceDiagnosticsService diagnosticsService = mock(LiveTradingBinanceDiagnosticsService.class);
        OperatorPermissionService permissionService = mock(OperatorPermissionService.class);
        ControlCenterSettingsProvider controlCenterSettingsProvider = mock(ControlCenterSettingsProvider.class);

        Recommendation recommendation = recommendation("BTCUSDT", Instant.now().minusSeconds(60));
        when(recommendationRepository.findById(recommendation.getId())).thenReturn(Optional.of(recommendation));
        when(executionRepository.findFirstByRecommendation_IdAndExecutionStateInOrderByCreatedAtDesc(
                recommendation.getId(),
                List.of(
                        LiveTradeExecutionState.REQUESTED,
                        LiveTradeExecutionState.SUBMITTING,
                        LiveTradeExecutionState.ENTRY_SUBMITTED,
                        LiveTradeExecutionState.PROTECTION_SUBMITTED,
                        LiveTradeExecutionState.OPEN,
                        LiveTradeExecutionState.PENDING_RECONCILE,
                        LiveTradeExecutionState.EMERGENCY_CLOSE_SUBMITTED))).thenReturn(Optional.empty());
        when(placeabilityService.evaluate(recommendation.getId())).thenReturn(placeable());
        when(binanceClient.getSymbolInfo("BTCUSDT")).thenReturn(Optional.of(symbolInfo()));
        when(permissionService.isPermissionEnabled("live.execution.enabled")).thenReturn(true);
        when(controlCenterSettingsProvider.isLiveExecutionReadOnly()).thenReturn(true);
        when(diagnosticsService.evaluate("BTCUSDT")).thenReturn(validBinanceDiagnostics());

        LiveTradingPreflightService service = new LiveTradingPreflightService(
                recommendationRepository,
                executionRepository,
                placeabilityService,
                binanceClient,
                diagnosticsService,
                permissionService,
                controlCenterSettingsProvider,
                objectMapper,
                new AppProperties());

        LiveTradingPreflightDTO dto = service.evaluate(recommendation.getId());

        assertFalse(dto.isExecutable());
        assertTrue(dto.getRuntime().isReadOnly());
        assertEquals(LiveTradingBlockerCodes.BOT_READ_ONLY, dto.getBlockedReasons().getFirst().getCode());
        assertEquals("Trading is disabled. Bot is in READ-ONLY mode.", dto.getBlockedReasons().getFirst().getMessage());
    }

    @Test
    void duplicateActiveExecutionSetsRuntimeDuplicateBlocker() throws Exception {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        RecommendationPlaceabilityService placeabilityService = mock(RecommendationPlaceabilityService.class);
        BinanceClient binanceClient = mock(BinanceClient.class);
        LiveTradingBinanceDiagnosticsService diagnosticsService = mock(LiveTradingBinanceDiagnosticsService.class);
        OperatorPermissionService permissionService = mock(OperatorPermissionService.class);
        ControlCenterSettingsProvider controlCenterSettingsProvider = mock(ControlCenterSettingsProvider.class);

        Recommendation recommendation = recommendation("BTCUSDT", Instant.now().minusSeconds(60));
        LiveTradeExecution activeExecution = new LiveTradeExecution();
        activeExecution.setId(UUID.randomUUID());
        activeExecution.setClientRequestId(UUID.randomUUID());
        activeExecution.setExecutionState(LiveTradeExecutionState.OPEN);
        activeExecution.setCreatedAt(Instant.now().minusSeconds(10));

        when(recommendationRepository.findById(recommendation.getId())).thenReturn(Optional.of(recommendation));
        when(executionRepository.findFirstByRecommendation_IdAndExecutionStateInOrderByCreatedAtDesc(
                recommendation.getId(),
                List.of(
                        LiveTradeExecutionState.REQUESTED,
                        LiveTradeExecutionState.SUBMITTING,
                        LiveTradeExecutionState.ENTRY_SUBMITTED,
                        LiveTradeExecutionState.PROTECTION_SUBMITTED,
                        LiveTradeExecutionState.OPEN,
                        LiveTradeExecutionState.PENDING_RECONCILE,
                        LiveTradeExecutionState.EMERGENCY_CLOSE_SUBMITTED))).thenReturn(Optional.of(activeExecution));
        when(placeabilityService.evaluate(recommendation.getId())).thenReturn(placeable());
        when(binanceClient.getSymbolInfo("BTCUSDT")).thenReturn(Optional.of(symbolInfo()));
        when(permissionService.isPermissionEnabled("live.execution.enabled")).thenReturn(true);
        when(controlCenterSettingsProvider.isLiveExecutionReadOnly()).thenReturn(false);
        when(diagnosticsService.evaluate("BTCUSDT")).thenReturn(validBinanceDiagnostics());

        LiveTradingPreflightService service = new LiveTradingPreflightService(
                recommendationRepository,
                executionRepository,
                placeabilityService,
                binanceClient,
                diagnosticsService,
                permissionService,
                controlCenterSettingsProvider,
                objectMapper,
                new AppProperties());

        LiveTradingPreflightDTO dto = service.evaluate(recommendation.getId());

        assertFalse(dto.isExecutable());
        assertTrue(dto.getRuntime().isDuplicateSubmitBlocked());
        assertTrue(dto.getBlockedReasons().stream()
                .anyMatch(reason -> LiveTradingBlockerCodes.DUPLICATE_SUBMIT_BLOCKED.equals(reason.getCode())));
    }

    @Test
    void ipAllowlistFailureIsNotMislabeledAsGenericAuth() throws Exception {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        LiveTradeExecutionRepository executionRepository = mock(LiveTradeExecutionRepository.class);
        RecommendationPlaceabilityService placeabilityService = mock(RecommendationPlaceabilityService.class);
        BinanceClient binanceClient = mock(BinanceClient.class);
        LiveTradingBinanceDiagnosticsService diagnosticsService = mock(LiveTradingBinanceDiagnosticsService.class);
        OperatorPermissionService permissionService = mock(OperatorPermissionService.class);
        ControlCenterSettingsProvider controlCenterSettingsProvider = mock(ControlCenterSettingsProvider.class);

        Recommendation recommendation = recommendation("BTCUSDT", Instant.now().minusSeconds(60));
        when(recommendationRepository.findById(recommendation.getId())).thenReturn(Optional.of(recommendation));
        when(executionRepository.findFirstByRecommendation_IdAndExecutionStateInOrderByCreatedAtDesc(
                recommendation.getId(),
                List.of(
                        LiveTradeExecutionState.REQUESTED,
                        LiveTradeExecutionState.SUBMITTING,
                        LiveTradeExecutionState.ENTRY_SUBMITTED,
                        LiveTradeExecutionState.PROTECTION_SUBMITTED,
                        LiveTradeExecutionState.OPEN,
                        LiveTradeExecutionState.PENDING_RECONCILE,
                        LiveTradeExecutionState.EMERGENCY_CLOSE_SUBMITTED))).thenReturn(Optional.empty());
        when(placeabilityService.evaluate(recommendation.getId())).thenReturn(placeable());
        when(binanceClient.getSymbolInfo("BTCUSDT")).thenReturn(Optional.of(symbolInfo()));
        when(permissionService.isPermissionEnabled("live.execution.enabled")).thenReturn(true);
        when(controlCenterSettingsProvider.isLiveExecutionReadOnly()).thenReturn(false);
        when(diagnosticsService.evaluate("BTCUSDT")).thenReturn(ipBlockedDiagnostics());

        LiveTradingPreflightService service = new LiveTradingPreflightService(
                recommendationRepository,
                executionRepository,
                placeabilityService,
                binanceClient,
                diagnosticsService,
                permissionService,
                controlCenterSettingsProvider,
                objectMapper,
                new AppProperties());

        LiveTradingPreflightDTO dto = service.evaluate(recommendation.getId());

        assertFalse(dto.isExecutable());
        assertEquals(LiveTradingBlockerCodes.BINANCE_IP_NOT_ALLOWED, dto.getBinance().getBlockerCode());
        assertTrue(dto.getBlockedReasons().stream()
                .anyMatch(reason -> LiveTradingBlockerCodes.BINANCE_IP_NOT_ALLOWED.equals(reason.getCode())));
        assertFalse(dto.getBlockedReasons().stream()
                .anyMatch(reason -> LiveTradingBlockerCodes.BINANCE_AUTH_INVALID.equals(reason.getCode())));
    }

    private Recommendation recommendation(String symbol, Instant createdAt) throws Exception {
        Recommendation recommendation = new Recommendation();
        recommendation.setId(UUID.randomUUID());
        recommendation.setScanRun(new ScanRun());
        recommendation.getScanRun().setId(UUID.randomUUID());
        recommendation.setSymbol(symbol);
        recommendation.setSide("BUY");
        recommendation.setCreatedAt(createdAt);
        recommendation.setStatus("NEW");

        OrderFields orderFields = new OrderFields();
        orderFields.setRecommendationId(recommendation.getId());
        orderFields.setRecommendation(recommendation);
        orderFields.setEntryOrderJson("""
                {"symbol":"BTCUSDT","side":"BUY","type":"MARKET","quantity":0.010}
                """);
        orderFields.setSlOrderJson("""
                {"symbol":"BTCUSDT","side":"SELL","type":"STOP_MARKET","stopPrice":98000.0,"closePosition":true}
                """);
        orderFields.setTpOrderJson("""
                {"symbol":"BTCUSDT","side":"SELL","type":"TAKE_PROFIT_MARKET","stopPrice":104000.0,"closePosition":true}
                """);
        orderFields.setLeverageRecommendation(5);
        orderFields.setMarginMode("ISOLATED");
        orderFields.setPositionMode("ONE_WAY");
        recommendation.setOrderFields(orderFields);
        return recommendation;
    }

    private RecommendationPlaceabilityDTO placeable() {
        RecommendationPlaceabilityDTO dto = new RecommendationPlaceabilityDTO();
        dto.setManualPlacementAllowed(true);
        dto.setPlaceable(true);
        dto.setReasonCode("OK");
        dto.setReasonText("Trade is placeable against LIVE MARK.");
        dto.setMarkPrice(new BigDecimal("100500.0"));
        return dto;
    }

    private BinanceExchangeInfoResponse.SymbolInfo symbolInfo() {
        BinanceExchangeInfoResponse.Filter price = new BinanceExchangeInfoResponse.Filter();
        price.setFilterType("PRICE_FILTER");
        price.setTickSize("0.1");

        BinanceExchangeInfoResponse.Filter marketLot = new BinanceExchangeInfoResponse.Filter();
        marketLot.setFilterType("MARKET_LOT_SIZE");
        marketLot.setStepSize("0.001");
        marketLot.setMinQty("0.001");

        BinanceExchangeInfoResponse.Filter notional = new BinanceExchangeInfoResponse.Filter();
        notional.setFilterType("NOTIONAL");
        notional.setNotional("5");

        BinanceExchangeInfoResponse.SymbolInfo symbolInfo = new BinanceExchangeInfoResponse.SymbolInfo();
        symbolInfo.setSymbol("BTCUSDT");
        symbolInfo.setStatus("TRADING");
        symbolInfo.setContractType("PERPETUAL");
        symbolInfo.setQuoteAsset("USDT");
        symbolInfo.setFilters(List.of(price, marketLot, notional));
        return symbolInfo;
    }

    private LiveTradingPreflightDTO.Binance validBinanceDiagnostics() {
        LiveTradingPreflightDTO.Binance diagnostics = new LiveTradingPreflightDTO.Binance();
        diagnostics.setCredentialsPresent(true);
        diagnostics.setAuthValid(true);
        diagnostics.setFuturesOrderReadOk(true);
        diagnostics.setPositionModeReadOk(true);
        diagnostics.setFuturesPermissionOk(true);
        diagnostics.setIpAllowlistOk(true);
        diagnostics.setTimestampOk(true);
        diagnostics.setSigningOk(true);
        diagnostics.setEndpointFamily("BINANCE_FUTURES");
        diagnostics.setBaseUrl("https://fapi.binance.com");
        diagnostics.setSpotBaseUrl("https://api.binance.com");
        diagnostics.setRecvWindowMs(5000L);
        return diagnostics;
    }

    private LiveTradingPreflightDTO.Binance ipBlockedDiagnostics() {
        LiveTradingPreflightDTO.Binance diagnostics = validBinanceDiagnostics();
        diagnostics.setAuthValid(false);
        diagnostics.setIpAllowlistOk(false);
        diagnostics.setFuturesPermissionOk(false);
        diagnostics.setSigningOk(false);
        diagnostics.setTimestampOk(false);
        diagnostics.setBlockerCode(LiveTradingBlockerCodes.BINANCE_IP_NOT_ALLOWED);
        diagnostics.setBlockerMessage("Binance rejected the backend host IP. Verify the Binance trusted IP allowlist.");
        diagnostics.setRequestIpHint("203.0.113.10");
        return diagnostics;
    }
}
