package com.tradebot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.client.BinanceClient;
import com.tradebot.config.AppProperties;
import com.tradebot.dto.BinanceExchangeInfoResponse;
import com.tradebot.dto.BinanceOrderFieldsDTO;
import com.tradebot.dto.LiveTradingPreflightDTO;
import com.tradebot.dto.RecommendationPlaceabilityDTO;
import com.tradebot.entity.OrderFields;
import com.tradebot.entity.Recommendation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ExchangeExecutionPreflightService {

    private final BinanceClient binanceClient;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final ExchangeRoundingService exchangeRoundingService;

    public ExchangeExecutionPreflightResult evaluate(Recommendation recommendation,
            RecommendationPlaceabilityDTO placeability) {
        OrderPayloads payloads = parseOrderPayloads(recommendation != null ? recommendation.getOrderFields() : null);
        if (recommendation == null || payloads == null) {
            return ExchangeExecutionPreflightResult.invalid(List.of("Recommendation order payloads are missing or invalid."));
        }

        Optional<BinanceExchangeInfoResponse.SymbolInfo> symbolInfoOptional = binanceClient.getSymbolInfo(recommendation.getSymbol());
        if (symbolInfoOptional.isEmpty()) {
            return ExchangeExecutionPreflightResult.invalid(List.of("Symbol is not present in Binance Futures exchange info."));
        }

        BinanceExchangeInfoResponse.SymbolInfo symbolInfo = symbolInfoOptional.get();
        BigDecimal markPrice = parseBigDecimal(placeability != null ? placeability.getMarkPrice() : null);
        if (markPrice == null) {
            try {
                markPrice = binanceClient.getMarkPrice(recommendation.getSymbol());
            } catch (Exception ignored) {
                markPrice = null;
            }
        }

        BigDecimal tickSize = nonZero(symbolInfo.getTickSize());
        BigDecimal stepSize = nonZero(symbolInfo.getMarketStepSize());
        if (stepSize == null) {
            stepSize = nonZero(symbolInfo.getStepSize());
        }
        BigDecimal minQty = nonZero(symbolInfo.getMarketMinQty());
        if (minQty == null) {
            minQty = nonZero(symbolInfo.getMinQty());
        }
        BigDecimal minNotional = nonZero(symbolInfo.getMinNotional());

        BinanceOrderFieldsDTO roundedEntry = copy(payloads.entry());
        BinanceOrderFieldsDTO roundedSl = copy(payloads.sl());
        BinanceOrderFieldsDTO roundedTp = copy(payloads.tp());

        roundedEntry.setQuantity(exchangeRoundingService.roundQuantityDown(roundedEntry.getQuantity(), stepSize));
        roundedSl.setStopPrice(exchangeRoundingService.roundPriceTowardReference(roundedSl.getStopPrice(), markPrice, tickSize));
        roundedTp.setStopPrice(exchangeRoundingService.roundPriceTowardReference(roundedTp.getStopPrice(), markPrice, tickSize));

        List<String> failures = new ArrayList<>();
        if (!"TRADING".equalsIgnoreCase(symbolInfo.getStatus())) {
            failures.add("Symbol is not currently tradable on Binance Futures.");
        }
        if (!"PERPETUAL".equalsIgnoreCase(symbolInfo.getContractType())
                || !"USDT".equalsIgnoreCase(symbolInfo.getQuoteAsset())) {
            failures.add("Symbol is not a USDT perpetual futures contract.");
        }
        if (markPrice == null || markPrice.compareTo(BigDecimal.ZERO) <= 0) {
            failures.add("Live mark price is unavailable for execution preflight.");
        }
        if (tickSize == null) {
            failures.add("Binance price filter tick size is unavailable.");
        }
        if (stepSize == null) {
            failures.add("Binance market lot size step is unavailable.");
        }
        if (minQty == null) {
            failures.add("Binance minimum quantity rule is unavailable.");
        }
        if (roundedEntry.getQuantity() == null || roundedEntry.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            failures.add("Entry quantity is missing or rounds down to zero.");
        }
        if (roundedSl.getStopPrice() == null || roundedTp.getStopPrice() == null) {
            failures.add("Protection order stop prices are missing or invalid after rounding.");
        }

        String marginMode = recommendation.getOrderFields() != null ? recommendation.getOrderFields().getMarginMode() : null;
        String positionMode = recommendation.getOrderFields() != null ? recommendation.getOrderFields().getPositionMode() : null;
        Integer leverage = recommendation.getOrderFields() != null
                ? recommendation.getOrderFields().getLeverageRecommendation()
                : appProperties.getLeverage();

        if (!"ISOLATED".equalsIgnoreCase(marginMode)) {
            failures.add("Execution requires isolated margin mode.");
        }
        if (!"ONE_WAY".equalsIgnoreCase(positionMode)) {
            failures.add("Execution requires one-way position mode.");
        }

        if (minQty != null && roundedEntry.getQuantity() != null && roundedEntry.getQuantity().compareTo(minQty) < 0) {
            failures.add("Entry quantity is below Binance minimum quantity.");
        }
        if (markPrice != null && roundedEntry.getQuantity() != null) {
            BigDecimal notional = roundedEntry.getQuantity().multiply(markPrice);
            if (minNotional != null && notional.compareTo(minNotional) < 0) {
                failures.add("Entry notional is below Binance minimum notional.");
            }
        }

        if (markPrice != null && roundedSl.getStopPrice() != null && !validProtectionDirection(roundedEntry, roundedSl, markPrice)) {
            failures.add("Stop-loss trigger is no longer safe relative to the live mark price.");
        }
        if (markPrice != null && roundedTp.getStopPrice() != null && !validProtectionDirection(roundedEntry, roundedTp, markPrice)) {
            failures.add("Take-profit trigger is no longer safe relative to the live mark price.");
        }

        return new ExchangeExecutionPreflightResult(
                payloads,
                new OrderPayloads(roundedEntry, roundedSl, roundedTp),
                symbolInfo,
                markPrice,
                tickSize,
                stepSize,
                minQty,
                minNotional,
                leverage,
                marginMode,
                positionMode,
                failures);
    }

    public void applyTo(LiveTradingPreflightDTO dto, ExchangeExecutionPreflightResult result) {
        if (dto == null || result == null) {
            return;
        }
        dto.getExchangeValidation().setMarkPrice(result.markPrice());
        dto.getExchangeValidation().setTickSize(result.tickSize());
        dto.getExchangeValidation().setStepSize(result.stepSize());
        dto.getExchangeValidation().setMinQty(result.minQty());
        dto.getExchangeValidation().setMinNotional(result.minNotional());
        dto.getExchangeValidation().setLeverage(result.leverage());
        dto.getExchangeValidation().setMarginMode(result.marginMode());
        dto.getExchangeValidation().setPositionMode(result.positionMode());
        if (result.roundedPayloads() != null) {
            dto.getExchangeValidation().setQuantity(result.roundedPayloads().entry().getQuantity());
            dto.getExchangeValidation().setSlStopPrice(result.roundedPayloads().sl().getStopPrice());
            dto.getExchangeValidation().setTpStopPrice(result.roundedPayloads().tp().getStopPrice());
            if (result.markPrice() != null && result.roundedPayloads().entry().getQuantity() != null) {
                dto.getExchangeValidation().setEntryNotionalUsdt(result.roundedPayloads().entry().getQuantity().multiply(result.markPrice()));
            }
        }
        dto.getExchangeValidation().setValid(result.valid());
        dto.getExchangeValidation().getFailures().clear();
        dto.getExchangeValidation().getFailures().addAll(result.failures());
    }

    private boolean validProtectionDirection(BinanceOrderFieldsDTO entry, BinanceOrderFieldsDTO protection, BigDecimal referencePrice) {
        if (entry == null || protection == null || referencePrice == null) {
            return false;
        }
        boolean longEntry = "BUY".equalsIgnoreCase(entry.getSide());
        String normalizedType = protection.getType() == null ? "" : protection.getType().trim().toUpperCase();
        return switch (normalizedType) {
            case "STOP_MARKET" -> longEntry
                    ? protection.getStopPrice().compareTo(referencePrice) < 0
                    : protection.getStopPrice().compareTo(referencePrice) > 0;
            case "TAKE_PROFIT_MARKET" -> longEntry
                    ? protection.getStopPrice().compareTo(referencePrice) > 0
                    : protection.getStopPrice().compareTo(referencePrice) < 0;
            default -> false;
        };
    }

    private OrderPayloads parseOrderPayloads(OrderFields orderFields) {
        if (orderFields == null) {
            return null;
        }
        try {
            return new OrderPayloads(
                    objectMapper.readValue(orderFields.getEntryOrderJson(), BinanceOrderFieldsDTO.class),
                    objectMapper.readValue(orderFields.getSlOrderJson(), BinanceOrderFieldsDTO.class),
                    objectMapper.readValue(orderFields.getTpOrderJson(), BinanceOrderFieldsDTO.class));
        } catch (Exception ex) {
            return null;
        }
    }

    private BinanceOrderFieldsDTO copy(BinanceOrderFieldsDTO source) {
        if (source == null) {
            return new BinanceOrderFieldsDTO();
        }
        BinanceOrderFieldsDTO copy = new BinanceOrderFieldsDTO();
        copy.setSymbol(source.getSymbol());
        copy.setSide(source.getSide());
        copy.setType(source.getType());
        copy.setQuantity(source.getQuantity());
        copy.setPrice(source.getPrice());
        copy.setStopPrice(source.getStopPrice());
        copy.setReduceOnly(source.getReduceOnly());
        copy.setClosePosition(source.getClosePosition());
        copy.setWorkingType(source.getWorkingType());
        copy.setTimeInForce(source.getTimeInForce());
        copy.setPositionSide(source.getPositionSide());
        return copy;
    }

    private BigDecimal parseBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private BigDecimal nonZero(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? null : value;
    }

    public record OrderPayloads(BinanceOrderFieldsDTO entry, BinanceOrderFieldsDTO sl, BinanceOrderFieldsDTO tp) {
    }

    public record ExchangeExecutionPreflightResult(
            OrderPayloads originalPayloads,
            OrderPayloads roundedPayloads,
            BinanceExchangeInfoResponse.SymbolInfo symbolInfo,
            BigDecimal markPrice,
            BigDecimal tickSize,
            BigDecimal stepSize,
            BigDecimal minQty,
            BigDecimal minNotional,
            Integer leverage,
            String marginMode,
            String positionMode,
            List<String> failures) {

        public boolean valid() {
            return failures == null || failures.isEmpty();
        }

        public Map<String, Object> details() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("markPrice", markPrice);
            payload.put("tickSize", tickSize);
            payload.put("stepSize", stepSize);
            payload.put("minQty", minQty);
            payload.put("minNotional", minNotional);
            payload.put("leverage", leverage);
            payload.put("marginMode", marginMode);
            payload.put("positionMode", positionMode);
            payload.put("failures", failures);
            if (roundedPayloads != null) {
                payload.put("entryQuantity", roundedPayloads.entry().getQuantity());
                payload.put("stopLossPrice", roundedPayloads.sl().getStopPrice());
                payload.put("takeProfitPrice", roundedPayloads.tp().getStopPrice());
            }
            return payload;
        }

        public static ExchangeExecutionPreflightResult invalid(List<String> failures) {
            return new ExchangeExecutionPreflightResult(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    failures);
        }
    }
}
