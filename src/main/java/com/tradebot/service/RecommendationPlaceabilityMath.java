package com.tradebot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class RecommendationPlaceabilityMath {

    private static final BigDecimal MIN_RR_FLOOR = new BigDecimal("2.0");

    private RecommendationPlaceabilityMath() {
    }

    public record PlaceabilityResult(
            String inequalityRule,
            String requiredInequality,
            boolean tpOk,
            boolean slOk,
            boolean rrOk,
            BigDecimal rrToTp1,
            BigDecimal minRrRequired,
            BigDecimal suggestedTp1Adjusted,
            BigDecimal suggestedSlAdjusted,
            List<String> violations,
            List<String> adjustments,
            boolean placeable) {
    }

    public static PlaceabilityResult evaluate(
            String side,
            BigDecimal mark,
            BigDecimal tickSize,
            BigDecimal tpRaw,
            BigDecimal slRaw,
            BigDecimal configuredMinRr) {

        if (mark == null || tickSize == null || tpRaw == null || slRaw == null) {
            throw new IllegalArgumentException("mark, tickSize, tpRaw and slRaw are required.");
        }
        if (tickSize.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("tickSize must be > 0.");
        }

        boolean isLong = isLongSide(side);
        BigDecimal minRrRequired = normalizeMinRr(configuredMinRr);
        List<String> violations = new ArrayList<>();
        List<String> adjustments = new ArrayList<>();

        BigDecimal minTpForLong = mark.add(tickSize);
        BigDecimal maxSlForLong = mark.subtract(tickSize);
        BigDecimal maxTpForShort = mark.subtract(tickSize);
        BigDecimal minSlForShort = mark.add(tickSize);

        boolean tpOk;
        boolean slOk;
        BigDecimal suggestedTp = null;
        BigDecimal suggestedSl = null;

        if (isLong) {
            tpOk = tpRaw.compareTo(minTpForLong) >= 0;
            slOk = slRaw.compareTo(maxSlForLong) <= 0;
            if (!tpOk) {
                suggestedTp = roundUpToTick(minTpForLong, tickSize);
                adjustments.add("Suggested TP1 moved to MARK + 1 tick boundary.");
                violations.add(String.format("LONG TP must be >= MARK + 1 tick (%s).", minTpForLong.toPlainString()));
            }
            if (!slOk) {
                suggestedSl = roundDownToTick(maxSlForLong, tickSize);
                adjustments.add("Suggested SL moved to MARK - 1 tick boundary.");
                violations.add(String.format("LONG SL must be <= MARK - 1 tick (%s).", maxSlForLong.toPlainString()));
            }
        } else {
            tpOk = tpRaw.compareTo(maxTpForShort) <= 0;
            slOk = slRaw.compareTo(minSlForShort) >= 0;
            if (!tpOk) {
                suggestedTp = roundDownToTick(maxTpForShort, tickSize);
                adjustments.add("Suggested TP1 moved to MARK - 1 tick boundary.");
                violations.add(String.format("SHORT TP must be <= MARK - 1 tick (%s).", maxTpForShort.toPlainString()));
            }
            if (!slOk) {
                suggestedSl = roundUpToTick(minSlForShort, tickSize);
                adjustments.add("Suggested SL moved to MARK + 1 tick boundary.");
                violations.add(String.format("SHORT SL must be >= MARK + 1 tick (%s).", minSlForShort.toPlainString()));
            }
        }

        BigDecimal rr = computeRrToTp1(mark, tpRaw, slRaw);
        boolean rrOk = rr != null && rr.compareTo(minRrRequired) >= 0;
        if (!rrOk) {
            if (rr == null) {
                violations.add("Live RR to TP1 could not be computed from MARK/TP/SL.");
            } else {
                violations.add(String.format("Live RR to TP1 (%s) is below minimum required (%s).",
                        rr.toPlainString(), minRrRequired.toPlainString()));
            }
        }

        boolean placeable = tpOk && slOk && rrOk;
        return new PlaceabilityResult(
                isLong ? "TP > MARK > SL" : "SL > MARK > TP",
                isLong
                        ? "LONG requires TP >= MARK + 1 tick and SL <= MARK - 1 tick."
                        : "SHORT requires SL >= MARK + 1 tick and TP <= MARK - 1 tick.",
                tpOk,
                slOk,
                rrOk,
                rr,
                minRrRequired,
                suggestedTp,
                suggestedSl,
                List.copyOf(violations),
                List.copyOf(adjustments),
                placeable);
    }

    public static boolean isLongSide(String side) {
        return "BUY".equalsIgnoreCase(side) || "LONG".equalsIgnoreCase(side);
    }

    public static BigDecimal computeRrToTp1(BigDecimal mark, BigDecimal tp, BigDecimal sl) {
        if (mark == null || tp == null || sl == null) {
            return null;
        }
        BigDecimal reward = tp.subtract(mark).abs();
        BigDecimal risk = mark.subtract(sl).abs();
        if (risk.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return reward.divide(risk, 4, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    public static BigDecimal roundUpToTick(BigDecimal value, BigDecimal tick) {
        if (value == null || tick == null || tick.compareTo(BigDecimal.ZERO) <= 0) {
            return value;
        }
        return value.divide(tick, 0, RoundingMode.CEILING).multiply(tick);
    }

    public static BigDecimal roundDownToTick(BigDecimal value, BigDecimal tick) {
        if (value == null || tick == null || tick.compareTo(BigDecimal.ZERO) <= 0) {
            return value;
        }
        return value.divide(tick, 0, RoundingMode.FLOOR).multiply(tick);
    }

    private static BigDecimal normalizeMinRr(BigDecimal configuredMinRr) {
        if (configuredMinRr == null || configuredMinRr.compareTo(BigDecimal.ZERO) <= 0) {
            return MIN_RR_FLOOR;
        }
        return configuredMinRr.max(MIN_RR_FLOOR).stripTrailingZeros();
    }
}
