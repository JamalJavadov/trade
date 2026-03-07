package com.tradebot.service.scan;

import com.tradebot.dto.Candle;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DataIntegrityValidationService {

    private static final int MIN_REQUIRED_CANDLES = 50;

    public StageAudit evaluate(FrozenSymbolSnapshot snapshot) {
        Instant startedAt = Instant.now();
        List<StageFinding> findings = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();

        validateMetadata(snapshot, findings, details);
        validateCandles("execution", snapshot.executionTf(), snapshot.executionCandles(), snapshot.snapshotAt(), findings, details);
        validateCandles("bias", snapshot.biasTf(), snapshot.biasCandles(), snapshot.snapshotAt(), findings, details);

        boolean hasCritical = findings.stream().anyMatch(finding -> "CRITICAL".equalsIgnoreCase(finding.severity()));
        boolean hasWarnings = findings.stream().anyMatch(finding -> "WARN".equalsIgnoreCase(finding.severity()));
        String status = hasCritical ? "FAILED" : hasWarnings ? "FRAGILE" : "PASSED";

        details.putIfAbsent("executionCandleCount", snapshot.executionCandles() != null ? snapshot.executionCandles().size() : 0);
        details.putIfAbsent("biasCandleCount", snapshot.biasCandles() != null ? snapshot.biasCandles().size() : 0);
        return new StageAudit(
                DeepScanStage.DATA_INTEGRITY,
                status,
                List.copyOf(findings),
                immutable(details),
                startedAt,
                Instant.now());
    }

    private void validateMetadata(FrozenSymbolSnapshot snapshot, List<StageFinding> findings, Map<String, Object> details) {
        Map<String, Object> metadata = snapshot.exchangeMetadata();
        details.put("metadataPresent", metadata != null && !metadata.isEmpty());
        if (metadata == null || metadata.isEmpty()) {
            findings.add(new StageFinding("MISSING_EXCHANGE_METADATA", "CRITICAL", "Exchange metadata is missing for the symbol.", Map.of()));
            return;
        }

        checkEquals(metadata, "contractType", "PERPETUAL", "UNSUPPORTED_CONTRACT_TYPE", findings);
        checkEquals(metadata, "quoteAsset", "USDT", "UNSUPPORTED_QUOTE_ASSET", findings);
        checkEquals(metadata, "status", "TRADING", "SYMBOL_NOT_TRADING", findings);
        checkPositive(metadata, "tickSize", "INVALID_TICK_SIZE", findings);
        checkPositive(metadata, "stepSize", "INVALID_STEP_SIZE", findings);
        checkPositive(metadata, "minQty", "INVALID_MIN_QTY", findings);
    }

    private void validateCandles(
            String label,
            String tf,
            List<Candle> candles,
            Instant snapshotAt,
            List<StageFinding> findings,
            Map<String, Object> details) {
        if (candles == null || candles.isEmpty()) {
            findings.add(new StageFinding(label.toUpperCase() + "_CANDLES_MISSING", "CRITICAL",
                    "Required candle set is missing.", detail("timeframe", tf)));
            return;
        }
        if (candles.size() < MIN_REQUIRED_CANDLES) {
            findings.add(new StageFinding(label.toUpperCase() + "_INSUFFICIENT_CANDLES", "CRITICAL",
                    "Candle set is too short for deterministic evaluation.", detail("timeframe", tf, "count", candles.size())));
        }

        long expectedMillis = intervalMillis(tf);
        int gapCount = 0;
        for (int i = 0; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            if (hasMalformedValues(candle)) {
                findings.add(new StageFinding(label.toUpperCase() + "_MALFORMED_CANDLE", "CRITICAL",
                        "Candle values are malformed or non-positive.",
                        detail("timeframe", tf, "index", i, "openTime", candle != null ? candle.getOpenTime() : null)));
                continue;
            }
            if (i > 0) {
                Candle previous = candles.get(i - 1);
                if (previous.getOpenTime() == null || candle.getOpenTime() == null || !candle.getOpenTime().isAfter(previous.getOpenTime())) {
                    findings.add(new StageFinding(label.toUpperCase() + "_OUT_OF_ORDER", "CRITICAL",
                            "Candles are out of order or duplicated.", detail("timeframe", tf, "index", i)));
                } else {
                    long delta = Duration.between(previous.getOpenTime(), candle.getOpenTime()).toMillis();
                    if (Math.abs(delta - expectedMillis) > Math.max(1_000L, expectedMillis / 10)) {
                        gapCount++;
                    }
                }
            }
        }

        if (gapCount > 0) {
            findings.add(new StageFinding(label.toUpperCase() + "_GAP_DETECTED", "CRITICAL",
                    "Candle continuity check detected missing or cutoff bars.",
                    detail("timeframe", tf, "gapCount", gapCount)));
        }

        Candle last = candles.get(candles.size() - 1);
        if (snapshotAt != null && last != null && last.getCloseTime() != null) {
            long ageMillis = Duration.between(last.getCloseTime(), snapshotAt).toMillis();
            details.put(label + "AgeMs", ageMillis);
            if (ageMillis > expectedMillis * 3) {
                findings.add(new StageFinding(label.toUpperCase() + "_STALE", "CRITICAL",
                        "Latest candle snapshot is stale for the locked timeframe.",
                        detail("timeframe", tf, "ageMillis", ageMillis)));
            } else if (ageMillis > expectedMillis * 2) {
                findings.add(new StageFinding(label.toUpperCase() + "_LATE", "WARN",
                        "Latest candle snapshot is late but still inside fail-closed grace.",
                        detail("timeframe", tf, "ageMillis", ageMillis)));
            }
        }
    }

    private boolean hasMalformedValues(Candle candle) {
        if (candle == null || candle.getOpenTime() == null || candle.getCloseTime() == null) {
            return true;
        }
        if (!positive(candle.getOpen()) || !positive(candle.getHigh()) || !positive(candle.getLow()) || !positive(candle.getClose())) {
            return true;
        }
        if (candle.getHigh().compareTo(candle.getLow()) < 0) {
            return true;
        }
        BigDecimal maxBody = candle.getOpen().max(candle.getClose());
        BigDecimal minBody = candle.getOpen().min(candle.getClose());
        return candle.getHigh().compareTo(maxBody) < 0 || candle.getLow().compareTo(minBody) > 0;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private long intervalMillis(String tf) {
        return switch (tf == null ? "" : tf.trim().toLowerCase()) {
            case "1h" -> Duration.ofHours(1).toMillis();
            case "15m" -> Duration.ofMinutes(15).toMillis();
            case "5m" -> Duration.ofMinutes(5).toMillis();
            default -> Duration.ofMinutes(15).toMillis();
        };
    }

    private void checkEquals(
            Map<String, Object> metadata,
            String key,
            String expected,
            String code,
            List<StageFinding> findings) {
        Object value = metadata.get(key);
        if (value == null || !expected.equalsIgnoreCase(String.valueOf(value))) {
            findings.add(new StageFinding(code, "CRITICAL",
                    "Exchange metadata violates tradability assumptions.",
                    detail("field", key, "expected", expected, "actual", value)));
        }
    }

    private void checkPositive(Map<String, Object> metadata, String key, String code, List<StageFinding> findings) {
        Object value = metadata.get(key);
        BigDecimal decimal;
        try {
            decimal = value == null ? null : new BigDecimal(String.valueOf(value));
        } catch (Exception ex) {
            decimal = null;
        }
        if (decimal == null || decimal.compareTo(BigDecimal.ZERO) <= 0) {
            findings.add(new StageFinding(code, "CRITICAL",
                    "Exchange precision/filter metadata is missing or non-positive.",
                    detail("field", key, "actual", value)));
        }
    }

    private Map<String, Object> detail(Object... entries) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            detail.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return Collections.unmodifiableMap(detail);
    }

    private Map<String, Object> immutable(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
