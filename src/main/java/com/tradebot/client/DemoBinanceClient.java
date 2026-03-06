package com.tradebot.client;

import com.tradebot.config.DemoTradingProperties;
import com.tradebot.config.AppProperties;
import com.tradebot.dto.BinanceExchangeInfoResponse;
import com.tradebot.dto.BinancePremiumIndexResponse;
import com.tradebot.dto.BinanceTicker24hResponse;
import com.tradebot.dto.Candle;
import com.tradebot.guard.NoTradingGuard;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class DemoBinanceClient {

    private static final int MIN_EXCHANGE_INFO_BUFFER_BYTES = 256 * 1024;

    private final WebClient webClient;
    private final RateLimiter rateLimiter;
    private final CandleParser candleParser;
    private final Duration markTimeout = Duration.ofSeconds(3);
    private final Duration requestTimeout = Duration.ofSeconds(8);
    private final Duration exchangeInfoCacheTtl = Duration.ofSeconds(300);
    private final Object exchangeInfoCacheLock = new Object();
    private volatile BinanceExchangeInfoResponse exchangeInfoCache;
    private volatile Instant exchangeInfoCachedAt;

    public DemoBinanceClient(WebClient.Builder webClientBuilder,
            NoTradingGuard noTradingGuard,
            RateLimiter rateLimiter,
            CandleParser candleParser,
            DemoTradingProperties demoTradingProperties,
            AppProperties appProperties) {
        final int exchangeInfoBufferBytes = resolveExchangeInfoBufferBytes(appProperties);
        WebClient.Builder builder = webClientBuilder
                .baseUrl("https://fapi.binance.com")
                .filter(noTradingGuard.preventTradingFilter())
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(exchangeInfoBufferBytes));

        String demoApiKey = demoTradingProperties.getBinance() != null
                ? demoTradingProperties.getBinance().getApiKey()
                : null;
        if (demoApiKey != null && !demoApiKey.isBlank()) {
            builder.defaultHeader("X-MBX-APIKEY", demoApiKey);
        }

        this.webClient = builder.build();
        this.rateLimiter = rateLimiter;
        this.candleParser = candleParser;
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
        BinanceExchangeInfoResponse exchangeInfo = getExchangeInfoCached();
        return exchangeInfo.getSymbols().stream()
                .filter(s -> symbol.equalsIgnoreCase(s.getSymbol()))
                .findFirst()
                .map(BinanceExchangeInfoResponse.SymbolInfo::getTickSize)
                .filter(tick -> tick != null && tick.compareTo(BigDecimal.ZERO) > 0)
                .orElseThrow(() -> new IllegalArgumentException("Tick size not found for symbol: " + symbol));
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
}
