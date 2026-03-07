package com.tradebot.service.scan;

import com.tradebot.service.RecommendationPlaceabilityMath;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DeepStructuralValidationService {

    public StageAudit evaluate(FrozenSymbolSnapshot snapshot, DeterministicStrategyResult result) {
        Instant startedAt = Instant.now();
        if (result == null || !result.valid()) {
            Map<String, Object> skippedDetails = detail("decision", result != null ? result.decision() : null);
            return new StageAudit(
                    DeepScanStage.STRUCTURAL_VALIDATION,
                    "SKIPPED",
                    List.of(new StageFinding("DETERMINISTIC_NOT_VALID", "INFO",
                            "Structural validation is skipped when deterministic evaluation does not produce a valid setup.",
                            skippedDetails)),
                    skippedDetails,
                    startedAt,
                    Instant.now());
        }

        List<StageFinding> findings = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();

        BigDecimal entry = result.executionPlan().entryPrice();
        BigDecimal sl = result.executionPlan().slPrice();
        BigDecimal tp1 = result.executionPlan().tp1Price();
        BigDecimal rr = RecommendationPlaceabilityMath.computeRrToTp1(entry, tp1, sl);
        details.put("recomputedRrToTp1", rr);
        details.put("reportedRrToTp1", result.executionPlan().rrToTp1());

        if (!sideMatchesBias(result.bias(), result.side())) {
            findings.add(new StageFinding("BIAS_SIDE_MISMATCH", "CRITICAL",
                    "Side and bias disagree with the deterministic strategy output.",
                    detail("bias", result.bias(), "side", result.side())));
        }
        if (!priceGeometryValid(result.side(), entry, sl, tp1)) {
            findings.add(new StageFinding("PRICE_GEOMETRY_INVALID", "CRITICAL",
                    "Entry/SL/TP geometry is internally inconsistent for the setup direction.",
                    detail("entry", entry, "sl", sl, "tp1", tp1, "side", result.side())));
        }
        if (rr == null || rr.compareTo(result.executionPlan().rrToTp1()) != 0) {
            findings.add(new StageFinding("RR_INTEGRITY_MISMATCH", "CRITICAL",
                    "Recomputed RR does not match the persisted deterministic RR.",
                    detail("recomputed", rr, "reported", result.executionPlan().rrToTp1())));
        }

        if (result.impulse() == null || result.impulse().startIndex() >= result.impulse().endIndex()) {
            findings.add(new StageFinding("IMPULSE_INDEX_INVALID", "CRITICAL",
                    "Impulse indices are inverted or missing.", Map.of()));
        }

        if (result.fibLevels() != null && result.impulse() != null) {
            BigDecimal start = BigDecimal.valueOf(result.impulse().startPrice());
            BigDecimal end = BigDecimal.valueOf(result.impulse().endPrice());
            if (result.fibLevels().zero().compareTo(end) != 0 || result.fibLevels().hundred().compareTo(start) != 0) {
                findings.add(new StageFinding("FIB_ALIGNMENT_INVALID", "CRITICAL",
                        "Fib anchors no longer align with the detected impulse.", detail("start", start, "end", end)));
            }
        }

        if (result.setup() != null && result.fibLevels() != null) {
            BigDecimal reclaimClose = result.setup().reclaimCandle() != null ? result.setup().reclaimCandle().getClose() : null;
            if (!reclaimWithinZone(result.side(), reclaimClose, result.fibLevels().fifty(), result.fibLevels().golden())) {
                findings.add(new StageFinding("RECLAIM_OUTSIDE_ZONE", "CRITICAL",
                        "Reclaim close is outside the deterministic fib zone.", detail("reclaimClose", reclaimClose)));
            }
            if (!sweepBeyondGolden(result.side(), result.setup().sweepPrice(), result.fibLevels().golden())) {
                findings.add(new StageFinding("SWEEP_NOT_BEYOND_GOLDEN", "CRITICAL",
                        "Sweep price does not extend beyond the golden level.", detail("sweepPrice", result.setup().sweepPrice())));
            }
        }

        BigDecimal reclaimStrength = reclaimStrength(result);
        details.put("reclaimStrength", reclaimStrength);
        if (reclaimStrength != null && reclaimStrength.compareTo(new BigDecimal("0.20")) < 0) {
            findings.add(new StageFinding("NOISY_RECLAIM", "WARN",
                    "Reclaim candle body is small relative to its range; setup looks fragile.", detail("reclaimStrength", reclaimStrength)));
        }

        long sweepTouches = sweepTouches(result, snapshot);
        details.put("sweepTouches", sweepTouches);
        if (sweepTouches > 1) {
            findings.add(new StageFinding("REPEATED_SWEEP_TOUCHES", "WARN",
                    "Multiple sweep touches were observed before reclaim confirmation.", Map.of("count", sweepTouches)));
        }

        boolean hasCritical = findings.stream().anyMatch(finding -> "CRITICAL".equalsIgnoreCase(finding.severity()));
        boolean hasWarnings = findings.stream().anyMatch(finding -> "WARN".equalsIgnoreCase(finding.severity()));
        String status = hasCritical ? "FAILED" : hasWarnings ? "FRAGILE" : "PASSED";

        return new StageAudit(
                DeepScanStage.STRUCTURAL_VALIDATION,
                status,
                List.copyOf(findings),
                immutable(details),
                startedAt,
                Instant.now());
    }

    private boolean sideMatchesBias(String bias, String side) {
        if (bias == null || side == null) {
            return false;
        }
        return ("UPTREND".equalsIgnoreCase(bias) && "LONG".equalsIgnoreCase(side))
                || ("DOWNTREND".equalsIgnoreCase(bias) && "SHORT".equalsIgnoreCase(side));
    }

    private boolean priceGeometryValid(String side, BigDecimal entry, BigDecimal sl, BigDecimal tp1) {
        if (entry == null || sl == null || tp1 == null || side == null) {
            return false;
        }
        if ("LONG".equalsIgnoreCase(side)) {
            return tp1.compareTo(entry) > 0 && entry.compareTo(sl) > 0;
        }
        return sl.compareTo(entry) > 0 && entry.compareTo(tp1) > 0;
    }

    private boolean reclaimWithinZone(String side, BigDecimal reclaimClose, BigDecimal fifty, BigDecimal golden) {
        if (reclaimClose == null || fifty == null || golden == null || side == null) {
            return false;
        }
        BigDecimal lower = fifty.min(golden);
        BigDecimal upper = fifty.max(golden);
        return reclaimClose.compareTo(lower) >= 0 && reclaimClose.compareTo(upper) <= 0;
    }

    private boolean sweepBeyondGolden(String side, BigDecimal sweepPrice, BigDecimal golden) {
        if (sweepPrice == null || golden == null || side == null) {
            return false;
        }
        if ("LONG".equalsIgnoreCase(side)) {
            return sweepPrice.compareTo(golden) <= 0;
        }
        return sweepPrice.compareTo(golden) >= 0;
    }

    private BigDecimal reclaimStrength(DeterministicStrategyResult result) {
        if (result.setup() == null || result.setup().reclaimCandle() == null
                || result.setup().reclaimCandle().getHigh() == null
                || result.setup().reclaimCandle().getLow() == null
                || result.setup().reclaimCandle().getOpen() == null
                || result.setup().reclaimCandle().getClose() == null) {
            return null;
        }
        BigDecimal range = result.setup().reclaimCandle().getHigh().subtract(result.setup().reclaimCandle().getLow()).abs();
        if (range.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal body = result.setup().reclaimCandle().getClose().subtract(result.setup().reclaimCandle().getOpen()).abs();
        return body.divide(range, 4, RoundingMode.HALF_UP);
    }

    private long sweepTouches(DeterministicStrategyResult result, FrozenSymbolSnapshot snapshot) {
        if (result.setup() == null || result.fibLevels() == null || snapshot.executionCandles() == null) {
            return 0L;
        }
        BigDecimal golden = result.fibLevels().golden();
        return snapshot.executionCandles().stream()
                .filter(candle -> candle != null)
                .filter(candle -> "LONG".equalsIgnoreCase(result.side())
                        ? candle.getLow() != null && candle.getLow().compareTo(golden) <= 0
                        : candle.getHigh() != null && candle.getHigh().compareTo(golden) >= 0)
                .count();
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
