package com.tradebot.client;

import com.tradebot.config.AppProperties;
import com.tradebot.dto.BinanceExchangeInfoResponse;
import com.tradebot.dto.BinanceFuturesAlgoOrderCancelResponse;
import com.tradebot.dto.BinanceFuturesAlgoOrderResponse;
import com.tradebot.dto.BinanceFuturesOrderResponse;
import com.tradebot.dto.BinanceFuturesPositionRiskResponse;
import com.tradebot.dto.BinanceTicker24hResponse;
import com.tradebot.dto.Candle;
import com.tradebot.guard.NoTradingGuard;
import com.tradebot.service.BinanceCredentialService;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Primary
@Profile("smoke")
public class SmokeBinanceClient extends BinanceClient {

    private static final List<String> SMOKE_SYMBOLS = List.of("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT");

    private final AtomicLong orderIds = new AtomicLong(10_000L);
    private final BinanceExchangeInfoResponse exchangeInfo = buildExchangeInfo();
    private final ConcurrentMap<Long, BinanceFuturesOrderResponse> ordersById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BinanceFuturesOrderResponse> ordersByClientId = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, List<Map<String, Object>>> userTradesByOrderId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BigDecimal> positionQtyBySymbol = new ConcurrentHashMap<>();

    public SmokeBinanceClient(org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder,
            NoTradingGuard noTradingGuard,
            RateLimiter rateLimiter,
            CandleParser candleParser,
            AppProperties appProperties,
            BinanceCredentialService credentialService) {
        super(webClientBuilder, noTradingGuard, rateLimiter, candleParser, appProperties, credentialService);
    }

    @Override
    public BinanceExchangeInfoResponse getExchangeInfo() {
        return exchangeInfo;
    }

    @Override
    public List<BinanceTicker24hResponse> getTicker24h() {
        return SMOKE_SYMBOLS.stream()
                .map(symbol -> {
                    BinanceTicker24hResponse response = new BinanceTicker24hResponse();
                    response.setSymbol(symbol);
                    response.setQuoteVolume(new BigDecimal("1000000"));
                    return response;
                })
                .toList();
    }

    @Override
    public List<Candle> getKlines(String symbol, String interval, int limit) {
        BigDecimal close = priceFor(symbol);
        Instant end = Instant.now();
        return java.util.stream.IntStream.range(0, Math.max(1, limit))
                .mapToObj(index -> {
                    Instant openTime = end.minusSeconds((long) (limit - index) * 60L);
                    return new Candle(
                            openTime,
                            close,
                            close,
                            close,
                            close,
                            new BigDecimal("1000"),
                            openTime.plusSeconds(59));
                })
                .toList();
    }

    @Override
    public BigDecimal getMarkPrice(String symbol) {
        return priceFor(symbol);
    }

    @Override
    public Optional<BinanceExchangeInfoResponse.SymbolInfo> getSymbolInfo(String symbol) {
        return exchangeInfo.getSymbols().stream()
                .filter(item -> item.getSymbol().equalsIgnoreCase(symbol))
                .findFirst();
    }

    @Override
    public Map<String, Object> setLeverage(String symbol, int leverage) {
        return Map.of("symbol", symbol, "leverage", leverage, "status", "UNCHANGED");
    }

    @Override
    public Map<String, Object> ensureIsolatedMargin(String symbol) {
        return Map.of("symbol", symbol, "marginType", "ISOLATED", "status", "UNCHANGED");
    }

    @Override
    public Map<String, Object> ensureOneWayPositionMode() {
        return Map.of("dualSidePosition", false, "status", "UNCHANGED");
    }

    @Override
    public Boolean getDualSidePositionMode() {
        return Boolean.FALSE;
    }

    @Override
    public List<Map<String, Object>> getRecentFuturesOrders(String symbol, int limit) {
        return List.of();
    }

    @Override
    public Map<String, Object> getSpotAccount() {
        return Map.of("balances", List.of());
    }

    @Override
    public Long getFuturesServerTime() {
        return System.currentTimeMillis();
    }

    @Override
    public BinanceFuturesOrderResponse submitOrder(Map<String, String> params) {
        BinanceFuturesOrderResponse response = new BinanceFuturesOrderResponse();
        response.setOrderId(orderIds.incrementAndGet());
        response.setSymbol(params.get("symbol"));
        response.setStatus("FILLED");
        response.setClientOrderId(params.get("newClientOrderId"));
        response.setSide(params.get("side"));
        response.setType(params.get("type"));
        response.setPositionSide(params.getOrDefault("positionSide", "BOTH"));
        response.setExecutedQty(params.getOrDefault("quantity", "0"));
        response.setOrigQty(params.getOrDefault("quantity", "0"));
        response.setAvgPrice(priceFor(params.get("symbol")).toPlainString());
        response.setCumQuote(priceFor(params.get("symbol")).toPlainString());
        response.setReduceOnly(Boolean.valueOf(params.getOrDefault("reduceOnly", "false")));
        response.setClosePosition(Boolean.valueOf(params.getOrDefault("closePosition", "false")));
        response.setWorkingType("MARK_PRICE");
        response.setUpdateTime(System.currentTimeMillis());
        registerOrder(response);
        BigDecimal quantity = decimal(params.get("quantity"), new BigDecimal("0.010"));
        if (Boolean.TRUE.equals(response.getReduceOnly()) || Boolean.TRUE.equals(response.getClosePosition())) {
            positionQtyBySymbol.put(response.getSymbol(), BigDecimal.ZERO);
            userTradesByOrderId.put(response.getOrderId(), List.of(tradeRow(response, quantity, new BigDecimal("1.25"), new BigDecimal("0.05"))));
        } else {
            positionQtyBySymbol.put(response.getSymbol(), quantity);
            userTradesByOrderId.put(response.getOrderId(), List.of(tradeRow(response, quantity, BigDecimal.ZERO, new BigDecimal("0.02"))));
        }
        return response;
    }

    @Override
    public BinanceFuturesAlgoOrderResponse submitAlgoOrder(Map<String, String> params) {
        BinanceFuturesAlgoOrderResponse response = new BinanceFuturesAlgoOrderResponse();
        response.setAlgoId(orderIds.incrementAndGet());
        response.setClientAlgoId(params.get("clientAlgoId"));
        response.setAlgoType(params.get("algoType"));
        response.setAlgoStatus("NEW");
        response.setOrderType(params.get("type"));
        response.setSymbol(params.get("symbol"));
        response.setSide(params.get("side"));
        response.setPositionSide(params.getOrDefault("positionSide", "BOTH"));
        response.setWorkingType(params.getOrDefault("workingType", "MARK_PRICE"));
        response.setClosePosition(Boolean.valueOf(params.getOrDefault("closePosition", "true")));
        response.setTriggerPrice(params.get("triggerPrice"));
        response.setCreateTime(System.currentTimeMillis());
        response.setUpdateTime(System.currentTimeMillis());
        return response;
    }

    @Override
    public BinanceFuturesOrderResponse getOrder(String symbol, String clientOrderId, Long orderId) {
        if (orderId != null && ordersById.containsKey(orderId)) {
            return ordersById.get(orderId);
        }
        if (clientOrderId != null && ordersByClientId.containsKey(clientOrderId)) {
            return ordersByClientId.get(clientOrderId);
        }
        BinanceFuturesOrderResponse response = new BinanceFuturesOrderResponse();
        response.setOrderId(orderId != null ? orderId : orderIds.incrementAndGet());
        response.setSymbol(symbol);
        response.setStatus("FILLED");
        response.setClientOrderId(clientOrderId);
        response.setSide("BUY");
        response.setType("MARKET");
        response.setPositionSide("BOTH");
        response.setExecutedQty(defaultPositionQty(symbol).toPlainString());
        response.setOrigQty(defaultPositionQty(symbol).toPlainString());
        response.setAvgPrice(priceFor(symbol).toPlainString());
        response.setCumQuote(priceFor(symbol).toPlainString());
        response.setReduceOnly(response.getOrderId() >= 10_000L);
        response.setClosePosition(false);
        response.setWorkingType("MARK_PRICE");
        response.setUpdateTime(System.currentTimeMillis());
        registerOrder(response);
        return response;
    }

    @Override
    public BinanceFuturesAlgoOrderResponse getAlgoOrder(String clientAlgoId, Long algoId) {
        return null;
    }

    @Override
    public BinanceFuturesAlgoOrderCancelResponse cancelAlgoOrder(String clientAlgoId, Long algoId) {
        BinanceFuturesAlgoOrderCancelResponse response = new BinanceFuturesAlgoOrderCancelResponse();
        response.setAlgoId(algoId != null ? algoId : orderIds.incrementAndGet());
        response.setClientAlgoId(clientAlgoId);
        response.setCode("200");
        response.setMsg("CANCELLED");
        return response;
    }

    @Override
    public List<BinanceFuturesAlgoOrderResponse> getOpenAlgoOrders(String symbol, String algoType, Long algoId) {
        return List.of();
    }

    @Override
    public List<BinanceFuturesPositionRiskResponse> getPositionRisk(String symbol) {
        BinanceFuturesPositionRiskResponse response = new BinanceFuturesPositionRiskResponse();
        response.setSymbol(symbol);
        response.setPositionSide("BOTH");
        BigDecimal positionQty = positionQtyBySymbol.computeIfAbsent(symbol, this::defaultPositionQty);
        response.setPositionAmt(positionQty.toPlainString());
        response.setEntryPrice(positionQty.compareTo(BigDecimal.ZERO) > 0 ? priceFor(symbol).toPlainString() : "0");
        response.setBreakEvenPrice(positionQty.compareTo(BigDecimal.ZERO) > 0 ? priceFor(symbol).toPlainString() : "0");
        response.setMarkPrice(priceFor(symbol).toPlainString());
        response.setUnRealizedProfit("0");
        response.setLiquidationPrice("0");
        response.setIsolatedMargin("0");
        response.setNotional("0");
        response.setMarginAsset("USDT");
        response.setIsolatedWallet("0");
        response.setInitialMargin("0");
        response.setMaintMargin("0");
        response.setPositionInitialMargin("0");
        response.setOpenOrderInitialMargin("0");
        response.setUpdateTime(System.currentTimeMillis());
        return List.of(response);
    }

    @Override
    public List<Map<String, Object>> getUserTrades(String symbol, Long orderId, Long startTimeMs, Long endTimeMs) {
        if (orderId == null) {
            return List.of();
        }
        return userTradesByOrderId.computeIfAbsent(orderId, ignored -> {
            BinanceFuturesOrderResponse order = ordersById.get(orderId);
            boolean closeOrder = order != null
                    ? Boolean.TRUE.equals(order.getReduceOnly()) || Boolean.TRUE.equals(order.getClosePosition())
                    : orderId >= 10_000L;
            BigDecimal quantity = order != null
                    ? decimal(order.getExecutedQty(), defaultPositionQty(symbol))
                    : defaultPositionQty(symbol);
            return List.of(tradeRow(
                    order != null ? order : syntheticOrder(symbol, orderId),
                    quantity,
                    closeOrder ? new BigDecimal("1.25") : BigDecimal.ZERO,
                    closeOrder ? new BigDecimal("0.05") : new BigDecimal("0.02")));
        });
    }

    @Override
    public List<Map<String, Object>> getIncomeHistory(String symbol,
            String incomeType,
            Long startTimeMs,
            Long endTimeMs,
            int limit) {
        return List.of();
    }

    @Override
    public Map<String, Object> verifyFuturesAccount(String apiKey,
            String privateKeyOrSecret,
            String authMode,
            String credentialSource) {
        return Map.of(
                "canTrade", true,
                "totalWalletBalance", "1000.00",
                "assets", List.of());
    }

    @Override
    public Map<String, Object> verifyFuturesAccountConfig(String apiKey,
            String privateKeyOrSecret,
            String authMode,
            String credentialSource) {
        return Map.of(
                "dualSidePosition", false,
                "multiAssetsMargin", false);
    }

    private BinanceExchangeInfoResponse buildExchangeInfo() {
        BinanceExchangeInfoResponse response = new BinanceExchangeInfoResponse();
        response.setSymbols(SMOKE_SYMBOLS.stream().map(this::symbolInfo).toList());
        return response;
    }

    private void registerOrder(BinanceFuturesOrderResponse response) {
        if (response.getOrderId() != null) {
            ordersById.put(response.getOrderId(), response);
        }
        if (response.getClientOrderId() != null && !response.getClientOrderId().isBlank()) {
            ordersByClientId.put(response.getClientOrderId(), response);
        }
    }

    private Map<String, Object> tradeRow(BinanceFuturesOrderResponse order,
            BigDecimal quantity,
            BigDecimal realizedPnl,
            BigDecimal commission) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("orderId", order.getOrderId());
        row.put("symbol", order.getSymbol());
        row.put("qty", quantity.toPlainString());
        row.put("price", order.getAvgPrice());
        row.put("realizedPnl", realizedPnl.toPlainString());
        row.put("commission", commission.toPlainString());
        row.put("time", System.currentTimeMillis());
        return row;
    }

    private BinanceFuturesOrderResponse syntheticOrder(String symbol, Long orderId) {
        BinanceFuturesOrderResponse response = new BinanceFuturesOrderResponse();
        response.setOrderId(orderId);
        response.setSymbol(symbol);
        response.setStatus("FILLED");
        response.setClientOrderId("smoke-" + orderId);
        response.setSide("BUY");
        response.setType("MARKET");
        response.setPositionSide("BOTH");
        response.setExecutedQty(defaultPositionQty(symbol).toPlainString());
        response.setOrigQty(defaultPositionQty(symbol).toPlainString());
        response.setAvgPrice(priceFor(symbol).toPlainString());
        response.setCumQuote(priceFor(symbol).toPlainString());
        response.setReduceOnly(orderId != null && orderId >= 10_000L);
        response.setClosePosition(false);
        response.setWorkingType("MARK_PRICE");
        response.setUpdateTime(System.currentTimeMillis());
        return response;
    }

    private BigDecimal defaultPositionQty(String symbol) {
        if ("ETHUSDT".equalsIgnoreCase(symbol)) {
            return new BigDecimal("0.050");
        }
        if ("BNBUSDT".equalsIgnoreCase(symbol)) {
            return new BigDecimal("0.200");
        }
        if ("SOLUSDT".equalsIgnoreCase(symbol)) {
            return new BigDecimal("0.300");
        }
        return new BigDecimal("0.010");
    }

    private BigDecimal decimal(String value, BigDecimal fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private BinanceExchangeInfoResponse.SymbolInfo symbolInfo(String symbol) {
        BinanceExchangeInfoResponse.SymbolInfo symbolInfo = new BinanceExchangeInfoResponse.SymbolInfo();
        symbolInfo.setSymbol(symbol);
        symbolInfo.setStatus("TRADING");
        symbolInfo.setContractType("PERPETUAL");
        symbolInfo.setQuoteAsset("USDT");

        BinanceExchangeInfoResponse.Filter priceFilter = new BinanceExchangeInfoResponse.Filter();
        priceFilter.setFilterType("PRICE_FILTER");
        priceFilter.setTickSize("0.1");

        BinanceExchangeInfoResponse.Filter lotSize = new BinanceExchangeInfoResponse.Filter();
        lotSize.setFilterType("LOT_SIZE");
        lotSize.setStepSize("0.001");
        lotSize.setMinQty("0.001");

        BinanceExchangeInfoResponse.Filter marketLotSize = new BinanceExchangeInfoResponse.Filter();
        marketLotSize.setFilterType("MARKET_LOT_SIZE");
        marketLotSize.setStepSize("0.001");
        marketLotSize.setMinQty("0.001");

        BinanceExchangeInfoResponse.Filter notional = new BinanceExchangeInfoResponse.Filter();
        notional.setFilterType("MIN_NOTIONAL");
        notional.setMinNotional("5");

        symbolInfo.setFilters(List.of(priceFilter, lotSize, marketLotSize, notional));
        return symbolInfo;
    }

    private BigDecimal priceFor(String symbol) {
        if ("ETHUSDT".equalsIgnoreCase(symbol)) {
            return new BigDecimal("4000");
        }
        if ("BNBUSDT".equalsIgnoreCase(symbol)) {
            return new BigDecimal("600");
        }
        if ("SOLUSDT".equalsIgnoreCase(symbol)) {
            return new BigDecimal("200");
        }
        return new BigDecimal("100000");
    }
}
