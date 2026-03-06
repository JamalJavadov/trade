package com.tradebot.demo.service;

import com.tradebot.client.DemoBinanceClient;
import com.tradebot.dto.BinanceExchangeInfoResponse;
import com.tradebot.dto.BinanceTicker24hResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DemoMarketDataService {

    private static final Duration EXCHANGE_INFO_TTL = Duration.ofMinutes(10);

    private final DemoBinanceClient binanceClient;

    private final Object exchangeInfoLock = new Object();
    private volatile CachedExchangeInfo cachedExchangeInfo;

    public DemoUniverse buildTop300Universe() {
        CachedExchangeInfo exchangeInfo = getCachedExchangeInfo();
        List<BinanceTicker24hResponse> tickers = binanceClient.getTicker24h();
        List<DemoUniverseSymbol> top = tickers.stream()
                .filter(Objects::nonNull)
                .filter(t -> t.getSymbol() != null)
                .filter(t -> exchangeInfo.eligibleSymbols().contains(t.getSymbol()))
                .sorted(Comparator.comparing((BinanceTicker24hResponse t) -> defaultDecimal(t.getQuoteVolume()))
                        .reversed())
                .limit(300)
                .map(t -> new DemoUniverseSymbol(t.getSymbol(), defaultDecimal(t.getQuoteVolume())))
                .toList();
        return new DemoUniverse(top, exchangeInfo.filtersBySymbol());
    }

    public SymbolFilters getSymbolFilters(String symbol) {
        SymbolFilters filters = getCachedExchangeInfo().filtersBySymbol().get(symbol);
        if (filters == null) {
            throw new IllegalArgumentException("Symbol filters missing for " + symbol);
        }
        return filters;
    }

    public BigDecimal getMarkPrice(String symbol) {
        return binanceClient.getMarkPrice(symbol);
    }

    private CachedExchangeInfo getCachedExchangeInfo() {
        CachedExchangeInfo snapshot = cachedExchangeInfo;
        if (snapshot != null && snapshot.cachedAt().plus(EXCHANGE_INFO_TTL).isAfter(Instant.now())) {
            return snapshot;
        }

        synchronized (exchangeInfoLock) {
            snapshot = cachedExchangeInfo;
            if (snapshot != null && snapshot.cachedAt().plus(EXCHANGE_INFO_TTL).isAfter(Instant.now())) {
                return snapshot;
            }

            BinanceExchangeInfoResponse refreshed = binanceClient.getExchangeInfo();
            Map<String, SymbolFilters> filtersBySymbol = new ConcurrentHashMap<>();
            List<BinanceExchangeInfoResponse.SymbolInfo> symbols = refreshed != null && refreshed.getSymbols() != null
                    ? refreshed.getSymbols()
                    : Collections.emptyList();
            Set<String> eligibleSymbols = symbols.stream()
                    .filter(Objects::nonNull)
                    .filter(s -> "PERPETUAL".equals(s.getContractType()))
                    .filter(s -> "USDT".equals(s.getQuoteAsset()))
                    .filter(s -> "TRADING".equals(s.getStatus()))
                    .map(s -> {
                        filtersBySymbol.put(s.getSymbol(), new SymbolFilters(
                                normalizePositive(s.getTickSize()),
                                normalizePositive(s.getStepSize()),
                                normalizePositive(s.getMinQty())));
                        return s.getSymbol();
                    })
                    .collect(Collectors.toSet());

            CachedExchangeInfo rebuilt = new CachedExchangeInfo(Instant.now(), eligibleSymbols, Map.copyOf(filtersBySymbol));
            cachedExchangeInfo = rebuilt;
            return rebuilt;
        }
    }

    private BigDecimal normalizePositive(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }

    private BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record CachedExchangeInfo(
            Instant cachedAt,
            Set<String> eligibleSymbols,
            Map<String, SymbolFilters> filtersBySymbol) {
    }

    public record DemoUniverse(List<DemoUniverseSymbol> symbols, Map<String, SymbolFilters> filtersBySymbol) {
    }

    public record DemoUniverseSymbol(String symbol, BigDecimal quoteVolume) {
    }

    public record SymbolFilters(BigDecimal tickSize, BigDecimal stepSize, BigDecimal minQty) {
    }
}
