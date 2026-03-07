package com.tradebot.client;

import com.tradebot.config.AppProperties;
import com.tradebot.dto.BinanceExchangeInfoResponse;
import com.tradebot.dto.BinanceFuturesOrderResponse;
import com.tradebot.dto.BinancePremiumIndexResponse;
import com.tradebot.dto.BinanceTicker24hResponse;
import com.tradebot.dto.Candle;
import com.tradebot.guard.NoTradingGuard;
import com.tradebot.service.BinanceErrorClassifier;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class BinanceClient {

    private static final int MIN_EXCHANGE_INFO_BUFFER_BYTES = 256 * 1024;
    private static final String FUTURES_BASE_URL = "https://fapi.binance.com";
    private static final String SPOT_BASE_URL = "https://api.binance.com";
    private static final long SIGNED_RECV_WINDOW_MS = 5_000L;
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient webClient;
    private final WebClient tradingWebClient;
    private final WebClient spotTradingWebClient;
    private final RateLimiter rateLimiter;
    private final CandleParser candleParser;
    private final AppProperties appProperties;
    private final Duration markTimeout = Duration.ofSeconds(3);
    private final Duration requestTimeout = Duration.ofSeconds(8);
    private final Duration tradingRequestTimeout = Duration.ofSeconds(10);
    private final Duration exchangeInfoCacheTtl = Duration.ofSeconds(300);
    private final Object exchangeInfoCacheLock = new Object();
    private volatile BinanceExchangeInfoResponse exchangeInfoCache;
    private volatile Instant exchangeInfoCachedAt;
    private volatile long futuresTimeOffsetMs;

    public BinanceClient(WebClient.Builder webClientBuilder,
            NoTradingGuard noTradingGuard,
            RateLimiter rateLimiter,
            CandleParser candleParser,
            AppProperties appProperties) {
        final int exchangeInfoBufferBytes = resolveExchangeInfoBufferBytes(appProperties);
        String apiKey = appProperties != null && appProperties.getBinance() != null
                ? appProperties.getBinance().getApiKey()
                : null;
        this.webClient = webClientBuilder.clone()
                .baseUrl(FUTURES_BASE_URL)
                .filter(noTradingGuard.preventTradingFilter())
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(exchangeInfoBufferBytes))
                .build();
        WebClient.Builder tradingBuilder = webClientBuilder.clone()
                .baseUrl(FUTURES_BASE_URL)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(exchangeInfoBufferBytes));
        if (apiKey != null && !apiKey.isBlank()) {
            tradingBuilder.defaultHeader("X-MBX-APIKEY", apiKey);
        }
        this.tradingWebClient = tradingBuilder.build();
        WebClient.Builder spotTradingBuilder = webClientBuilder.clone()
                .baseUrl(SPOT_BASE_URL)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(exchangeInfoBufferBytes));
        if (apiKey != null && !apiKey.isBlank()) {
            spotTradingBuilder.defaultHeader("X-MBX-APIKEY", apiKey);
        }
        this.spotTradingWebClient = spotTradingBuilder.build();
        this.rateLimiter = rateLimiter;
        this.candleParser = candleParser;
        this.appProperties = appProperties;
    }

    private int resolveExchangeInfoBufferBytes(AppProperties appProperties) {
        if (appProperties == null || appProperties.getScanner() == null) {
            return MIN_EXCHANGE_INFO_BUFFER_BYTES;
        }
        return Math.max(
                appProperties.getScanner().getExchangeInfoMaxInMemoryBytes(),
                MIN_EXCHANGE_INFO_BUFFER_BYTES);
    }

    @CircuitBreaker(name = "binanceApi")
    @Retry(name = "binanceApi")
    public BinanceExchangeInfoResponse getExchangeInfo() {
        rateLimiter.consume(1);
        return webClient.get()
                .uri("/fapi/v1/exchangeInfo")
                .retrieve()
                .bodyToMono(BinanceExchangeInfoResponse.class)
                .timeout(requestTimeout)
                .block();
    }

    @CircuitBreaker(name = "binanceApi")
    @Retry(name = "binanceApi")
    public List<BinanceTicker24hResponse> getTicker24h() {
        rateLimiter.consume(40);
        return webClient.get()
                .uri("/fapi/v1/ticker/24hr")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<BinanceTicker24hResponse>>() {
                })
                .timeout(requestTimeout)
                .block();
    }

    @CircuitBreaker(name = "binanceApi")
    @Retry(name = "binanceApi")
    public List<Candle> getKlines(String symbol, String interval, int limit) {
        rateLimiter.consume(limit > 500 ? 10 : 5);
        List<List<Object>> rawKlines = webClient.get()
                .uri(builder -> builder.path("/fapi/v1/klines")
                        .queryParam("symbol", symbol)
                        .queryParam("interval", interval)
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<List<Object>>>() {
                })
                .timeout(requestTimeout)
                .block();

        return candleParser.parse(rawKlines);
    }

    @CircuitBreaker(name = "binanceApi")
    @Retry(name = "binanceApi")
    public BigDecimal getMarkPrice(String symbol) {
        rateLimiter.consume(1);
        BinancePremiumIndexResponse response = webClient.get()
                .uri(builder -> builder.path("/fapi/v1/premiumIndex")
                        .queryParam("symbol", symbol)
                        .build())
                .retrieve()
                .bodyToMono(BinancePremiumIndexResponse.class)
                .timeout(markTimeout)
                .block();

        if (response == null || response.getMarkPrice() == null || response.getMarkPrice().isBlank()) {
            throw new IllegalStateException("Unable to fetch mark price for symbol: " + symbol);
        }
        return new BigDecimal(response.getMarkPrice());
    }

    @CircuitBreaker(name = "binanceApi")
    @Retry(name = "binanceApi")
    public BigDecimal getTickSize(String symbol) {
        return getSymbolInfo(symbol)
                .map(BinanceExchangeInfoResponse.SymbolInfo::getTickSize)
                .filter(tick -> tick != null && tick.compareTo(BigDecimal.ZERO) > 0)
                .orElseThrow(() -> new IllegalArgumentException("Tick size not found for symbol: " + symbol));
    }

    public java.util.Optional<BinanceExchangeInfoResponse.SymbolInfo> getSymbolInfo(String symbol) {
        BinanceExchangeInfoResponse exchangeInfo = getExchangeInfoCached();
        if (exchangeInfo == null || exchangeInfo.getSymbols() == null) {
            return java.util.Optional.empty();
        }
        return exchangeInfo.getSymbols().stream()
                .filter(s -> symbol.equalsIgnoreCase(s.getSymbol()))
                .findFirst();
    }

    public boolean hasTradingCredentials() {
        return trimToNull(appProperties != null && appProperties.getBinance() != null
                ? appProperties.getBinance().getApiKey()
                : null) != null
                && trimToNull(appProperties != null && appProperties.getBinance() != null
                        ? appProperties.getBinance().getApiSecret()
                        : null) != null;
    }

    public String getFuturesBaseUrl() {
        return FUTURES_BASE_URL;
    }

    public String getSpotBaseUrl() {
        return SPOT_BASE_URL;
    }

    public long getSignedRecvWindowMs() {
        return SIGNED_RECV_WINDOW_MS;
    }

    @CircuitBreaker(name = "binanceApi")
    @Retry(name = "binanceApi")
    public Map<String, Object> setLeverage(String symbol, int leverage) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("leverage", String.valueOf(leverage));
        return executeSignedPost("/fapi/v1/leverage", params, MAP_TYPE, 1);
    }

    @CircuitBreaker(name = "binanceApi")
    @Retry(name = "binanceApi")
    public Map<String, Object> ensureIsolatedMargin(String symbol) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("marginType", "ISOLATED");
        try {
            return executeSignedPost("/fapi/v1/marginType", params, MAP_TYPE, 1);
        } catch (WebClientResponseException ex) {
            if (isBenignConfigurationResponse(ex, "No need to change margin type")) {
                return Map.of("status", "UNCHANGED", "message", "Margin type already isolated.");
            }
            throw ex;
        }
    }

    @CircuitBreaker(name = "binanceApi")
    @Retry(name = "binanceApi")
    public Map<String, Object> ensureOneWayPositionMode() {
        Boolean dualSidePosition = getDualSidePositionMode();
        if (Boolean.FALSE.equals(dualSidePosition)) {
            return Map.of("status", "UNCHANGED", "dualSidePosition", false);
        }

        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("dualSidePosition", "false");
        try {
            Map<String, Object> response = executeSignedPost("/fapi/v1/positionSide/dual", params, MAP_TYPE, 1);
            return Map.of("status", "UPDATED", "dualSidePosition", false, "response", response);
        } catch (WebClientResponseException ex) {
            if (isBenignConfigurationResponse(ex, "No need to change position side")) {
                return Map.of("status", "UNCHANGED", "dualSidePosition", false);
            }
            throw ex;
        }
    }

    @CircuitBreaker(name = "binanceApi")
    @Retry(name = "binanceApi")
    public Boolean getDualSidePositionMode() {
        Map<String, Object> response = executeSignedGet("/fapi/v1/positionSide/dual",
                new LinkedHashMap<>(),
                MAP_TYPE,
                1);
        Object raw = response.get("dualSidePosition");
        if (raw instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (raw instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return null;
    }

    @CircuitBreaker(name = "binanceApi")
    @Retry(name = "binanceApi")
    public List<Map<String, Object>> getRecentFuturesOrders(String symbol, int limit) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("limit", String.valueOf(Math.max(1, Math.min(limit, 10))));
        return executeSignedGet("/fapi/v1/allOrders", params, LIST_OF_MAP_TYPE, 5);
    }

    @CircuitBreaker(name = "binanceApi")
    @Retry(name = "binanceApi")
    public Map<String, Object> getSpotAccount() {
        return executeSignedSpotGet("/api/v3/account", new LinkedHashMap<>(), MAP_TYPE, 5);
    }

    @CircuitBreaker(name = "binanceApi")
    @Retry(name = "binanceApi")
    public Long getFuturesServerTime() {
        Map<String, Object> response = webClient.get()
                .uri("/fapi/v1/time")
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(requestTimeout)
                .block();
        if (response == null) {
            return null;
        }
        Object raw = response.get("serverTime");
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @CircuitBreaker(name = "binanceApi")
    @Retry(name = "binanceApi")
    public BinanceFuturesOrderResponse submitOrder(Map<String, String> params) {
        return executeSignedPost("/fapi/v1/order", new LinkedHashMap<>(params), BinanceFuturesOrderResponse.class, 1);
    }

    @CircuitBreaker(name = "binanceApi")
    @Retry(name = "binanceApi")
    public BinanceFuturesOrderResponse getOrder(String symbol, String clientOrderId, Long orderId) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        if (clientOrderId != null && !clientOrderId.isBlank()) {
            params.put("origClientOrderId", clientOrderId);
        } else if (orderId != null) {
            params.put("orderId", String.valueOf(orderId));
        } else {
            throw new IllegalArgumentException("Either clientOrderId or orderId is required");
        }
        return executeSignedGet("/fapi/v1/order", params, BinanceFuturesOrderResponse.class, 1);
    }

    private BinanceExchangeInfoResponse getExchangeInfoCached() {
        BinanceExchangeInfoResponse snapshot = exchangeInfoCache;
        Instant snapshotAt = exchangeInfoCachedAt;
        if (snapshot != null && snapshotAt != null && snapshotAt.plus(exchangeInfoCacheTtl).isAfter(Instant.now())) {
            return snapshot;
        }

        synchronized (exchangeInfoCacheLock) {
            snapshot = exchangeInfoCache;
            snapshotAt = exchangeInfoCachedAt;
            if (snapshot != null && snapshotAt != null && snapshotAt.plus(exchangeInfoCacheTtl).isAfter(Instant.now())) {
                return snapshot;
            }

            BinanceExchangeInfoResponse refreshed = getExchangeInfo();
            exchangeInfoCache = refreshed;
            exchangeInfoCachedAt = Instant.now();
            return refreshed;
        }
    }

    private <T> T executeSignedGet(String path,
            LinkedHashMap<String, String> params,
            Class<T> responseType,
            int weight) {
        return executeSigned(path, HttpMethod.GET, params, responseType, null, weight);
    }

    private <T> T executeSignedGet(String path,
            LinkedHashMap<String, String> params,
            ParameterizedTypeReference<T> responseType,
            int weight) {
        return executeSigned(path, HttpMethod.GET, params, null, responseType, weight);
    }

    private <T> T executeSignedPost(String path,
            LinkedHashMap<String, String> params,
            Class<T> responseType,
            int weight) {
        return executeSigned(path, HttpMethod.POST, params, responseType, null, weight);
    }

    private <T> T executeSignedPost(String path,
            LinkedHashMap<String, String> params,
            ParameterizedTypeReference<T> responseType,
            int weight) {
        return executeSigned(path, HttpMethod.POST, params, null, responseType, weight);
    }

    private <T> T executeSignedSpotGet(String path,
            LinkedHashMap<String, String> params,
            ParameterizedTypeReference<T> responseType,
            int weight) {
        return executeSigned(spotTradingWebClient, path, HttpMethod.GET, params, null, responseType, weight);
    }

    private <T> T executeSigned(String path,
            HttpMethod method,
            LinkedHashMap<String, String> params,
            Class<T> responseClass,
            ParameterizedTypeReference<T> responseType,
            int weight) {
        return executeSigned(tradingWebClient, path, method, params, responseClass, responseType, weight);
    }

    private <T> T executeSigned(WebClient signedClient,
            String path,
            HttpMethod method,
            LinkedHashMap<String, String> params,
            Class<T> responseClass,
            ParameterizedTypeReference<T> responseType,
            int weight) {
        ensureTradingCredentialsPresent();

        rateLimiter.consume(Math.max(weight, 1));
        return executeSignedOnce(signedClient, path, method, params, responseClass, responseType, true);
    }

    private <T> T executeSignedOnce(WebClient signedClient,
            String path,
            HttpMethod method,
            LinkedHashMap<String, String> params,
            Class<T> responseClass,
            ParameterizedTypeReference<T> responseType,
            boolean allowTimestampRetry) {
        LinkedHashMap<String, String> working = new LinkedHashMap<>(params);
        working.put("timestamp", String.valueOf(System.currentTimeMillis() + futuresTimeOffsetMs));
        working.put("recvWindow", String.valueOf(SIGNED_RECV_WINDOW_MS));

        String query = buildQueryString(working);
        String signature = sign(query);
        String uri = path + "?" + query + "&signature=" + encode(signature);

        WebClient.RequestHeadersSpec<?> request = method == HttpMethod.GET
                ? signedClient.get().uri(uri)
                : signedClient.post().uri(uri);

        try {
            if (responseClass != null) {
                return request.retrieve()
                        .bodyToMono(responseClass)
                        .timeout(tradingRequestTimeout)
                        .block();
            }
            return request.retrieve()
                    .bodyToMono(responseType)
                    .timeout(tradingRequestTimeout)
                    .block();
        } catch (WebClientResponseException ex) {
            if (allowTimestampRetry
                    && BinanceErrorClassifier.isTimestampInvalid(BinanceErrorClassifier.from(ex))
                    && syncFuturesTimeOffset()) {
                return executeSignedOnce(signedClient, path, method, params, responseClass, responseType, false);
            }
            throw ex;
        }
    }

    private void ensureTradingCredentialsPresent() {
        if (!hasTradingCredentials()) {
            throw new IllegalStateException("Binance trading credentials are missing.");
        }
    }

    private boolean syncFuturesTimeOffset() {
        Long serverTime = getFuturesServerTime();
        if (serverTime == null) {
            return false;
        }
        futuresTimeOffsetMs = serverTime - System.currentTimeMillis();
        return true;
    }

    private String buildQueryString(LinkedHashMap<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (!first) {
                sb.append('&');
            }
            sb.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            first = false;
        }
        return sb.toString();
    }

    private String sign(String payload) {
        try {
            String secret = appProperties.getBinance().getApiSecret();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte value : raw) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign Binance request.", ex);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean isBenignConfigurationResponse(WebClientResponseException ex, String expectedMessage) {
        String body = ex.getResponseBodyAsString();
        return ex.getStatusCode().is4xxClientError()
                && body != null
                && body.toLowerCase().contains(expectedMessage.toLowerCase());
    }
}
