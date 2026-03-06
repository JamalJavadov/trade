package com.tradebot.client;

import com.tradebot.dto.Candle;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;

@Component
public class CandleParser {

    public List<Candle> parse(List<List<Object>> rawKlines) {
        List<Candle> candles = new ArrayList<>();
        if (rawKlines == null)
            return candles;

        for (List<Object> raw : rawKlines) {
            if (raw.size() < 7)
                continue;

            Candle candle = new Candle();
            candle.setOpenTime(parseInstant(raw.get(0)));
            candle.setOpen(parseBigDecimal(raw.get(1)));
            candle.setHigh(parseBigDecimal(raw.get(2)));
            candle.setLow(parseBigDecimal(raw.get(3)));
            candle.setClose(parseBigDecimal(raw.get(4)));
            candle.setVolume(parseBigDecimal(raw.get(5)));
            candle.setCloseTime(parseInstant(raw.get(6)));

            candles.add(candle);
        }
        return candles;
    }

    private Instant parseInstant(Object value) {
        if (value instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue());
        } else if (value instanceof String s) {
            return Instant.ofEpochMilli(Long.parseLong(s));
        }
        throw new IllegalArgumentException("Cannot parse Instant from " + value);
    }

    private BigDecimal parseBigDecimal(Object value) {
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        } else if (value instanceof String s) {
            return new BigDecimal(s);
        }
        throw new IllegalArgumentException("Cannot parse BigDecimal from " + value);
    }
}
