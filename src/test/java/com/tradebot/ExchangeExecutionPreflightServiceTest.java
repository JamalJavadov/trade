package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
import com.tradebot.config.AppProperties;
import com.tradebot.dto.BinanceExchangeInfoResponse;
import com.tradebot.dto.BinanceOrderFieldsDTO;
import com.tradebot.dto.RecommendationPlaceabilityDTO;
import com.tradebot.entity.OrderFields;
import com.tradebot.entity.Recommendation;
import com.tradebot.service.ExchangeExecutionPreflightService;
import com.tradebot.service.ExchangeRoundingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExchangeExecutionPreflightServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BinanceClient binanceClient = mock(BinanceClient.class);
    private final ExchangeExecutionPreflightService service = new ExchangeExecutionPreflightService(
            binanceClient,
            objectMapper,
            new AppProperties(),
            new ExchangeRoundingService());

    @Test
    void roundsOrderPayloadsUsingExchangeFilters() throws Exception {
        Recommendation recommendation = recommendation(
                new BigDecimal("0.01234"),
                new BigDecimal("95.03"),
                new BigDecimal("104.96"));
        when(binanceClient.getSymbolInfo("BTCUSDT")).thenReturn(Optional.of(symbolInfo("0.1", "0.001", "0.001", "1")));

        ExchangeExecutionPreflightService.ExchangeExecutionPreflightResult result = service.evaluate(
                recommendation,
                placeability(new BigDecimal("100")));

        assertTrue(result.valid());
        assertEquals(new BigDecimal("0.012"), result.roundedPayloads().entry().getQuantity());
        assertEquals(new BigDecimal("95.1"), result.roundedPayloads().sl().getStopPrice());
        assertEquals(new BigDecimal("104.9"), result.roundedPayloads().tp().getStopPrice());
    }

    @Test
    void rejectsMinNotionalAfterRounding() throws Exception {
        Recommendation recommendation = recommendation(
                new BigDecimal("0.01234"),
                new BigDecimal("95.03"),
                new BigDecimal("104.96"));
        when(binanceClient.getSymbolInfo("BTCUSDT")).thenReturn(Optional.of(symbolInfo("0.1", "0.001", "0.001", "2")));

        ExchangeExecutionPreflightService.ExchangeExecutionPreflightResult result = service.evaluate(
                recommendation,
                placeability(new BigDecimal("100")));

        assertFalse(result.valid());
        assertTrue(result.failures().contains("Entry notional is below Binance minimum notional."));
    }

    @Test
    void rejectsUnsafeProtectionDirectionAgainstLiveMarkPrice() throws Exception {
        Recommendation recommendation = recommendation(
                new BigDecimal("0.020"),
                new BigDecimal("100.05"),
                new BigDecimal("99.95"));
        when(binanceClient.getSymbolInfo("BTCUSDT")).thenReturn(Optional.of(symbolInfo("0.1", "0.001", "0.001", "1")));

        ExchangeExecutionPreflightService.ExchangeExecutionPreflightResult result = service.evaluate(
                recommendation,
                placeability(new BigDecimal("100")));

        assertFalse(result.valid());
        assertTrue(result.failures().contains("Stop-loss trigger is no longer safe relative to the live mark price."));
        assertTrue(result.failures().contains("Take-profit trigger is no longer safe relative to the live mark price."));
    }

    private Recommendation recommendation(BigDecimal quantity, BigDecimal stopLoss, BigDecimal takeProfit) throws Exception {
        Recommendation recommendation = new Recommendation();
        recommendation.setId(UUID.randomUUID());
        recommendation.setSymbol("BTCUSDT");
        recommendation.setSide("BUY");

        OrderFields orderFields = new OrderFields();
        orderFields.setMarginMode("ISOLATED");
        orderFields.setPositionMode("ONE_WAY");
        orderFields.setLeverageRecommendation(10);
        orderFields.setEntryOrderJson(objectMapper.writeValueAsString(order("BTCUSDT", "BUY", "MARKET", quantity, null)));
        orderFields.setSlOrderJson(objectMapper.writeValueAsString(order("BTCUSDT", "SELL", "STOP_MARKET", null, stopLoss)));
        orderFields.setTpOrderJson(objectMapper.writeValueAsString(order("BTCUSDT", "SELL", "TAKE_PROFIT_MARKET", null, takeProfit)));
        recommendation.setOrderFields(orderFields);
        return recommendation;
    }

    private BinanceOrderFieldsDTO order(String symbol,
            String side,
            String type,
            BigDecimal quantity,
            BigDecimal stopPrice) {
        BinanceOrderFieldsDTO dto = new BinanceOrderFieldsDTO();
        dto.setSymbol(symbol);
        dto.setSide(side);
        dto.setType(type);
        dto.setQuantity(quantity);
        dto.setStopPrice(stopPrice);
        dto.setClosePosition(true);
        dto.setWorkingType("MARK_PRICE");
        return dto;
    }

    private RecommendationPlaceabilityDTO placeability(BigDecimal markPrice) {
        RecommendationPlaceabilityDTO dto = new RecommendationPlaceabilityDTO();
        dto.setMarkPrice(markPrice);
        dto.setManualPlacementAllowed(true);
        return dto;
    }

    private BinanceExchangeInfoResponse.SymbolInfo symbolInfo(String tickSize,
            String stepSize,
            String minQty,
            String minNotional) {
        BinanceExchangeInfoResponse.SymbolInfo symbolInfo = new BinanceExchangeInfoResponse.SymbolInfo();
        symbolInfo.setSymbol("BTCUSDT");
        symbolInfo.setStatus("TRADING");
        symbolInfo.setContractType("PERPETUAL");
        symbolInfo.setQuoteAsset("USDT");

        BinanceExchangeInfoResponse.Filter priceFilter = new BinanceExchangeInfoResponse.Filter();
        priceFilter.setFilterType("PRICE_FILTER");
        priceFilter.setTickSize(tickSize);

        BinanceExchangeInfoResponse.Filter marketLotSize = new BinanceExchangeInfoResponse.Filter();
        marketLotSize.setFilterType("MARKET_LOT_SIZE");
        marketLotSize.setStepSize(stepSize);
        marketLotSize.setMinQty(minQty);

        BinanceExchangeInfoResponse.Filter notionalFilter = new BinanceExchangeInfoResponse.Filter();
        notionalFilter.setFilterType("MIN_NOTIONAL");
        notionalFilter.setMinNotional(minNotional);

        symbolInfo.setFilters(List.of(priceFilter, marketLotSize, notionalFilter));
        return symbolInfo;
    }
}
