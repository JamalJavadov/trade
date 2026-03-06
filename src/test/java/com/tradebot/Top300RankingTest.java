package com.tradebot;

import com.tradebot.dto.BinanceTicker24hResponse;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.Comparator;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class Top300RankingTest {

    @Test
    void shouldFilterAndRankTickers() {
        Set<String> eligibleSymbols = Set.of("BTCUSDT", "ETHUSDT", "SOLUSDT");

        BinanceTicker24hResponse btc = new BinanceTicker24hResponse();
        btc.setSymbol("BTCUSDT");
        btc.setQuoteVolume(new BigDecimal("1000000"));

        BinanceTicker24hResponse eth = new BinanceTicker24hResponse();
        eth.setSymbol("ETHUSDT");
        eth.setQuoteVolume(new BigDecimal("500000"));

        BinanceTicker24hResponse sol = new BinanceTicker24hResponse();
        sol.setSymbol("SOLUSDT");
        sol.setQuoteVolume(new BigDecimal("2000000"));

        BinanceTicker24hResponse doge = new BinanceTicker24hResponse(); // Not eligible
        doge.setSymbol("DOGEUSDT");
        doge.setQuoteVolume(new BigDecimal("5000000"));

        List<BinanceTicker24hResponse> rawTickers = List.of(btc, eth, sol, doge);

        int topN = 2;

        List<BinanceTicker24hResponse> ranked = rawTickers.stream()
                .filter(t -> eligibleSymbols.contains(t.getSymbol()))
                .sorted(Comparator.comparing(BinanceTicker24hResponse::getQuoteVolume).reversed())
                .limit(topN)
                .toList();

        assertEquals(2, ranked.size());
        assertEquals("SOLUSDT", ranked.get(0).getSymbol()); // Highest eligible
        assertEquals("BTCUSDT", ranked.get(1).getSymbol()); // Second highest
    }
}
