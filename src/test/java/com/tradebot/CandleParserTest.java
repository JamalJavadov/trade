package com.tradebot;

import com.tradebot.client.CandleParser;
import com.tradebot.dto.Candle;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class CandleParserTest {

    private final CandleParser parser = new CandleParser();

    @Test
    void shouldParseValidCandleData() {
        List<Object> rawCandle = Arrays.asList(
                1499040000000L, // Open time
                "0.01634790", // Open
                "0.80000000", // High
                "0.01575800", // Low
                "0.01577100", // Close
                "148976.11427815", // Volume
                1499644799999L, // Close time
                "2434.19055334", // Quote volume
                308, // Trades
                "1756.87402397", // Taker buy volume
                "28.46694368", // Taker buy quote volume
                "17928899.62484339" // Ignore
        );

        List<Candle> result = parser.parse(List.of(rawCandle));

        assertEquals(1, result.size());
        Candle c = result.get(0);
        assertEquals(1499040000000L, c.getOpenTime().toEpochMilli());
        assertEquals(new BigDecimal("0.01634790"), c.getOpen());
        assertEquals(new BigDecimal("0.80000000"), c.getHigh());
        assertEquals(new BigDecimal("0.01575800"), c.getLow());
        assertEquals(new BigDecimal("0.01577100"), c.getClose());
        assertEquals(new BigDecimal("148976.11427815"), c.getVolume());
        assertEquals(1499644799999L, c.getCloseTime().toEpochMilli());
    }

    @Test
    void shouldSkipInvalidCandleData() {
        List<Object> shortCandle = Arrays.asList(
                1499040000000L,
                "0.01634790");

        List<Candle> result = parser.parse(List.of(shortCandle));
        assertTrue(result.isEmpty());
    }
}
