package com.tradebot.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class DemoPreflightMath {

    private static final BigDecimal MIN_RR_FLOOR = new BigDecimal("2.0");

    private DemoPreflightMath() {
    }

    public static PreflightResult evaluate(
            String side,
            BigDecimal mark,
            BigDecimal tickSize,
            BigDecimal tp1,
            BigDecimal sl,
            BigDecimal configuredMinRr) {

        if (mark == null || tickSize == null || tp1 == null || sl == null || tickSize.compareTo(BigDecimal.ZERO) <= 0) {
            return new PreflightResult(false, null, "INVALID_INPUT", "Missing mark/tick/tp/sl input.");
        }

        boolean isLong = isLong(side);
        BigDecimal requiredRr = normalizeMinRr(configuredMinRr);

        boolean tpOk;
        boolean slOk;
        if (isLong) {
            tpOk = tp1.compareTo(mark.add(tickSize)) >= 0;
            slOk = sl.compareTo(mark.subtract(tickSize)) <= 0;
        } else {
            slOk = sl.compareTo(mark.add(tickSize)) >= 0;
            tpOk = tp1.compareTo(mark.subtract(tickSize)) <= 0;
        }

        BigDecimal rrLive = computeRrToTp1(mark, tp1, sl);
        boolean rrOk = rrLive != null && rrLive.compareTo(requiredRr) >= 0;

        if (!tpOk || !slOk) {
            return new PreflightResult(false, rrLive, "PRICE_NEEDS_TICK_GAP", "TP/SL do not satisfy strict mark+/-tick inequality.");
        }
        if (!rrOk) {
            return new PreflightResult(false, rrLive, "RR_BELOW_2", "Live RR to TP1 is below minimum required.");
        }

        return new PreflightResult(true, rrLive, "OK", "Trade is placeable.");
    }

    public static BigDecimal computeRrToTp1(BigDecimal mark, BigDecimal tp1, BigDecimal sl) {
        if (mark == null || tp1 == null || sl == null) {
            return null;
        }

        BigDecimal reward = tp1.subtract(mark).abs();
        BigDecimal risk = mark.subtract(sl).abs();
        if (risk.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return reward.divide(risk, 6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    public static boolean isLong(String side) {
        return "LONG".equalsIgnoreCase(side) || "BUY".equalsIgnoreCase(side);
    }

    private static BigDecimal normalizeMinRr(BigDecimal configuredMinRr) {
        if (configuredMinRr == null || configuredMinRr.compareTo(BigDecimal.ZERO) <= 0) {
            return MIN_RR_FLOOR;
        }
        return configuredMinRr.max(MIN_RR_FLOOR);
    }

    public record PreflightResult(boolean placeable, BigDecimal rrLive, String reasonCode, String reasonText) {
    }
}
