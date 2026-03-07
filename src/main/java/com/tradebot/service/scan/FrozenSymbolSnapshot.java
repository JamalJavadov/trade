package com.tradebot.service.scan;

import com.tradebot.dto.Candle;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FrozenSymbolSnapshot(
        UUID scanRunId,
        String traceId,
        String symbol,
        int rankInUniverse,
        BigDecimal quoteVolumeUsdt,
        Instant snapshotAt,
        String executionTf,
        String biasTf,
        Map<String, Object> exchangeMetadata,
        List<Candle> executionCandles,
        List<Candle> biasCandles) {

    public Candle lastExecutionCandle() {
        return executionCandles == null || executionCandles.isEmpty() ? null : executionCandles.get(executionCandles.size() - 1);
    }

    public Candle lastBiasCandle() {
        return biasCandles == null || biasCandles.isEmpty() ? null : biasCandles.get(biasCandles.size() - 1);
    }

    public Map<String, Object> toSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scanRunId", scanRunId != null ? scanRunId.toString() : null);
        summary.put("traceId", traceId);
        summary.put("symbol", symbol);
        summary.put("rankInUniverse", rankInUniverse);
        summary.put("quoteVolumeUsdt", quoteVolumeUsdt);
        summary.put("snapshotAt", snapshotAt);
        summary.put("executionTf", executionTf);
        summary.put("biasTf", biasTf);
        summary.put("exchangeMetadata", exchangeMetadata);
        summary.put("executionCandleCount", executionCandles != null ? executionCandles.size() : 0);
        summary.put("biasCandleCount", biasCandles != null ? biasCandles.size() : 0);
        summary.put("executionWindow", candleWindow(executionCandles));
        summary.put("biasWindow", candleWindow(biasCandles));
        return summary;
    }

    private Map<String, Object> candleWindow(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return Map.of("count", 0);
        }
        Candle first = candles.get(0);
        Candle last = candles.get(candles.size() - 1);
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("count", candles.size());
        window.put("firstOpenTime", first.getOpenTime());
        window.put("lastOpenTime", last.getOpenTime());
        window.put("lastCloseTime", last.getCloseTime());
        if (last.getCloseTime() != null && snapshotAt != null) {
            window.put("ageSeconds", Duration.between(last.getCloseTime(), snapshotAt).toSeconds());
        }
        return window;
    }
}
