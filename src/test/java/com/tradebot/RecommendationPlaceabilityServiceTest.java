package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
import com.tradebot.config.AppProperties;
import com.tradebot.controlcenter.ControlCenterConfig;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.entity.OrderFields;
import com.tradebot.entity.Recommendation;
import com.tradebot.entity.ScanRun;
import com.tradebot.entity.SymbolEvaluation;
import com.tradebot.repository.RecommendationRepository;
import com.tradebot.repository.SymbolEvaluationRepository;
import com.tradebot.service.RecommendationPlaceabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecommendationPlaceabilityServiceTest {

    @Test
    void missingRecommendationReturnsNotFoundPayload() {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        SymbolEvaluationRepository symbolEvaluationRepository = mock(SymbolEvaluationRepository.class);
        BinanceClient binanceClient = mock(BinanceClient.class);
        AppProperties appProperties = baseProperties();
        ObjectMapper mapper = new ObjectMapper();

        RecommendationPlaceabilityService service = new RecommendationPlaceabilityService(
                recommendationRepository,
                symbolEvaluationRepository,
                binanceClient,
                appProperties,
                settingsProvider(),
                mapper);

        UUID recommendationId = UUID.randomUUID();
        when(recommendationRepository.findById(recommendationId)).thenReturn(Optional.empty());

        var dto = service.evaluate(recommendationId);
        assertFalse(dto.isPlaceable());
        assertEquals("NOT_FOUND", dto.getReasonCode());
    }

    @Test
    void missingDataReturnsMissingDataReason() {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        SymbolEvaluationRepository symbolEvaluationRepository = mock(SymbolEvaluationRepository.class);
        BinanceClient binanceClient = mock(BinanceClient.class);
        AppProperties appProperties = baseProperties();
        ObjectMapper mapper = new ObjectMapper();

        RecommendationPlaceabilityService service = new RecommendationPlaceabilityService(
                recommendationRepository,
                symbolEvaluationRepository,
                binanceClient,
                appProperties,
                settingsProvider(),
                mapper);

        UUID recommendationId = UUID.randomUUID();
        Recommendation rec = baseRecommendation(recommendationId, "BTCUSDT", "BUY");
        rec.setOrderFields(null);

        when(recommendationRepository.findById(recommendationId)).thenReturn(Optional.of(rec));
        when(symbolEvaluationRepository.findByScanRunIdAndSymbol(rec.getScanRun().getId(), rec.getSymbol()))
                .thenReturn(Optional.empty());

        var dto = service.evaluate(recommendationId);
        assertFalse(dto.isPlaceable());
        assertEquals("MISSING_DATA", dto.getReasonCode());
    }

    @Test
    void missingTickSizeReturnsMissingTickSize() throws Exception {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        SymbolEvaluationRepository symbolEvaluationRepository = mock(SymbolEvaluationRepository.class);
        BinanceClient binanceClient = mock(BinanceClient.class);
        AppProperties appProperties = baseProperties();
        ObjectMapper mapper = new ObjectMapper();

        RecommendationPlaceabilityService service = new RecommendationPlaceabilityService(
                recommendationRepository,
                symbolEvaluationRepository,
                binanceClient,
                appProperties,
                settingsProvider(),
                mapper);

        UUID recommendationId = UUID.randomUUID();
        Recommendation rec = recWithOrders(recommendationId, "BTCUSDT", "BUY", "104.0", "98.0", mapper);

        when(recommendationRepository.findById(recommendationId)).thenReturn(Optional.of(rec));
        when(binanceClient.getMarkPrice("BTCUSDT")).thenReturn(new BigDecimal("100.0"));
        when(binanceClient.getTickSize("BTCUSDT")).thenThrow(new IllegalArgumentException("missing tick"));

        var dto = service.evaluate(recommendationId);
        assertFalse(dto.isPlaceable());
        assertEquals("MISSING_TICKSIZE", dto.getReasonCode());
    }

    @Test
    void binance429ReturnsRateLimitReason() throws Exception {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        SymbolEvaluationRepository symbolEvaluationRepository = mock(SymbolEvaluationRepository.class);
        BinanceClient binanceClient = mock(BinanceClient.class);
        AppProperties appProperties = baseProperties();
        ObjectMapper mapper = new ObjectMapper();

        RecommendationPlaceabilityService service = new RecommendationPlaceabilityService(
                recommendationRepository,
                symbolEvaluationRepository,
                binanceClient,
                appProperties,
                settingsProvider(),
                mapper);

        UUID recommendationId = UUID.randomUUID();
        Recommendation rec = recWithOrders(recommendationId, "BTCUSDT", "BUY", "104.0", "98.0", mapper);

        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "30");
        WebClientResponseException ex = createWebClientResponseException(
                429,
                "Too Many Requests",
                headers,
                "https://fapi.binance.com/fapi/v1/premiumIndex");

        when(recommendationRepository.findById(recommendationId)).thenReturn(Optional.of(rec));
        when(binanceClient.getMarkPrice("BTCUSDT")).thenThrow(ex);

        var dto = service.evaluate(recommendationId);
        assertFalse(dto.isPlaceable());
        assertEquals("BINANCE_RATE_LIMIT", dto.getReasonCode());
        assertTrue(dto.getReasonText().contains("Retry after"));
    }

    @Test
    void longValidityReturnsPlaceableTrue() throws Exception {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        SymbolEvaluationRepository symbolEvaluationRepository = mock(SymbolEvaluationRepository.class);
        BinanceClient binanceClient = mock(BinanceClient.class);
        AppProperties appProperties = baseProperties();
        ObjectMapper mapper = new ObjectMapper();

        RecommendationPlaceabilityService service = new RecommendationPlaceabilityService(
                recommendationRepository,
                symbolEvaluationRepository,
                binanceClient,
                appProperties,
                settingsProvider(),
                mapper);

        UUID recommendationId = UUID.randomUUID();
        Recommendation rec = recWithOrders(recommendationId, "BTCUSDT", "BUY", "104.0", "98.0", mapper);

        when(recommendationRepository.findById(recommendationId)).thenReturn(Optional.of(rec));
        when(binanceClient.getMarkPrice("BTCUSDT")).thenReturn(new BigDecimal("100.0"));
        when(binanceClient.getTickSize("BTCUSDT")).thenReturn(new BigDecimal("0.1"));

        var dto = service.evaluate(recommendationId);
        assertTrue(dto.isPlaceable());
        assertEquals("OK", dto.getReasonCode());
        assertTrue(dto.getChecks().isTpOk());
        assertTrue(dto.getChecks().isSlOk());
        assertTrue(dto.getChecks().isRrOk());
    }

    @Test
    void shortValidityReturnsPlaceableTrue() throws Exception {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        SymbolEvaluationRepository symbolEvaluationRepository = mock(SymbolEvaluationRepository.class);
        BinanceClient binanceClient = mock(BinanceClient.class);
        AppProperties appProperties = baseProperties();
        ObjectMapper mapper = new ObjectMapper();

        RecommendationPlaceabilityService service = new RecommendationPlaceabilityService(
                recommendationRepository,
                symbolEvaluationRepository,
                binanceClient,
                appProperties,
                settingsProvider(),
                mapper);

        UUID recommendationId = UUID.randomUUID();
        Recommendation rec = recWithOrders(recommendationId, "ETHUSDT", "SELL", "96.0", "102.0", mapper);

        when(recommendationRepository.findById(recommendationId)).thenReturn(Optional.of(rec));
        when(binanceClient.getMarkPrice("ETHUSDT")).thenReturn(new BigDecimal("100.0"));
        when(binanceClient.getTickSize("ETHUSDT")).thenReturn(new BigDecimal("0.1"));

        var dto = service.evaluate(recommendationId);
        assertTrue(dto.isPlaceable());
        assertEquals("OK", dto.getReasonCode());
    }

    @Test
    void rrBelow2ReturnsBlockedReason() throws Exception {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        SymbolEvaluationRepository symbolEvaluationRepository = mock(SymbolEvaluationRepository.class);
        BinanceClient binanceClient = mock(BinanceClient.class);
        AppProperties appProperties = baseProperties();
        ObjectMapper mapper = new ObjectMapper();

        RecommendationPlaceabilityService service = new RecommendationPlaceabilityService(
                recommendationRepository,
                symbolEvaluationRepository,
                binanceClient,
                appProperties,
                settingsProvider(),
                mapper);

        UUID recommendationId = UUID.randomUUID();
        Recommendation rec = recWithOrders(recommendationId, "ETHUSDT", "SELL", "99.85", "100.1", mapper);

        when(recommendationRepository.findById(recommendationId)).thenReturn(Optional.of(rec));
        when(binanceClient.getMarkPrice("ETHUSDT")).thenReturn(new BigDecimal("100.0"));
        when(binanceClient.getTickSize("ETHUSDT")).thenReturn(new BigDecimal("0.1"));

        var dto = service.evaluate(recommendationId);
        assertFalse(dto.isPlaceable());
        assertEquals("RR_BELOW_2", dto.getReasonCode());
    }

    @Test
    void tickGapViolationReturnsSuggestedAdjustments() throws Exception {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        SymbolEvaluationRepository symbolEvaluationRepository = mock(SymbolEvaluationRepository.class);
        BinanceClient binanceClient = mock(BinanceClient.class);
        AppProperties appProperties = baseProperties();
        ObjectMapper mapper = new ObjectMapper();

        RecommendationPlaceabilityService service = new RecommendationPlaceabilityService(
                recommendationRepository,
                symbolEvaluationRepository,
                binanceClient,
                appProperties,
                settingsProvider(),
                mapper);

        UUID recommendationId = UUID.randomUUID();
        Recommendation rec = recWithOrders(recommendationId, "XRPUSDT", "SELL", "99.5", "100.05", mapper);

        when(recommendationRepository.findById(recommendationId)).thenReturn(Optional.of(rec));
        when(binanceClient.getMarkPrice("XRPUSDT")).thenReturn(new BigDecimal("100.0"));
        when(binanceClient.getTickSize("XRPUSDT")).thenReturn(new BigDecimal("0.1"));

        var dto = service.evaluate(recommendationId);
        assertFalse(dto.isPlaceable());
        assertEquals("PRICE_NEEDS_TICK_GAP", dto.getReasonCode());
        assertNotNull(dto.getComputed().getSuggestedSlAdjusted());
    }

    @Test
    void fallbackToMetricsWhenOrderFieldsMissing() throws Exception {
        RecommendationRepository recommendationRepository = mock(RecommendationRepository.class);
        SymbolEvaluationRepository symbolEvaluationRepository = mock(SymbolEvaluationRepository.class);
        BinanceClient binanceClient = mock(BinanceClient.class);
        AppProperties appProperties = baseProperties();
        ObjectMapper mapper = new ObjectMapper();

        RecommendationPlaceabilityService service = new RecommendationPlaceabilityService(
                recommendationRepository,
                symbolEvaluationRepository,
                binanceClient,
                appProperties,
                settingsProvider(),
                mapper);

        UUID recommendationId = UUID.randomUUID();
        Recommendation rec = baseRecommendation(recommendationId, "SOLUSDT", "BUY");
        rec.setOrderFields(null);

        SymbolEvaluation evaluation = new SymbolEvaluation();
        evaluation.setMetricsJson(mapper.writeValueAsString(Map.of("tp1", "104.0", "sl", "98.0")));

        when(recommendationRepository.findById(recommendationId)).thenReturn(Optional.of(rec));
        when(symbolEvaluationRepository.findByScanRunIdAndSymbol(rec.getScanRun().getId(), rec.getSymbol()))
                .thenReturn(Optional.of(evaluation));
        when(binanceClient.getMarkPrice("SOLUSDT")).thenReturn(new BigDecimal("100.0"));
        when(binanceClient.getTickSize("SOLUSDT")).thenReturn(new BigDecimal("0.1"));

        var dto = service.evaluate(recommendationId);
        assertTrue(dto.isPlaceable());
        assertEquals("OK", dto.getReasonCode());
    }

    private AppProperties baseProperties() {
        AppProperties appProperties = new AppProperties();
        appProperties.getStrategy().setMinRr(new BigDecimal("2.0"));
        return appProperties;
    }

    private ControlCenterSettingsProvider settingsProvider() {
        ControlCenterSettingsProvider provider = mock(ControlCenterSettingsProvider.class);
        ControlCenterConfig config = new ControlCenterConfig();
        config.getStrategyLocks().setMinRr(new BigDecimal("2.0"));
        when(provider.getConfigSnapshot()).thenReturn(config);
        return provider;
    }

    private Recommendation baseRecommendation(UUID recommendationId, String symbol, String side) {
        Recommendation rec = new Recommendation();
        rec.setId(recommendationId);
        rec.setSymbol(symbol);
        rec.setSide(side);
        rec.setCreatedAt(Instant.now());
        rec.setStatus("NEW");
        ScanRun run = new ScanRun();
        run.setId(UUID.randomUUID());
        rec.setScanRun(run);
        return rec;
    }

    private Recommendation recWithOrders(UUID recommendationId, String symbol, String side, String tp, String sl,
            ObjectMapper mapper) throws Exception {
        Recommendation rec = baseRecommendation(recommendationId, symbol, side);
        OrderFields orderFields = new OrderFields();
        orderFields.setTpOrderJson(mapper.writeValueAsString(Map.of(
                "symbol", symbol,
                "side", "BUY".equalsIgnoreCase(side) ? "SELL" : "BUY",
                "type", "TAKE_PROFIT_MARKET",
                "stopPrice", tp,
                "workingType", "MARK_PRICE")));
        orderFields.setSlOrderJson(mapper.writeValueAsString(Map.of(
                "symbol", symbol,
                "side", "BUY".equalsIgnoreCase(side) ? "SELL" : "BUY",
                "type", "STOP_MARKET",
                "stopPrice", sl,
                "workingType", "MARK_PRICE")));
        rec.setOrderFields(orderFields);
        return rec;
    }

    private WebClientResponseException createWebClientResponseException(int status, String text, HttpHeaders headers,
            String uri) {
        org.springframework.http.HttpRequest request = mock(org.springframework.http.HttpRequest.class);
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        when(request.getURI()).thenReturn(URI.create(uri));
        return WebClientResponseException.create(
                status,
                text,
                headers,
                null,
                StandardCharsets.UTF_8,
                request);
    }
}
