package com.tradebot.demo.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class DemoRiskSizer {

    private static final BigDecimal MAX_EQUITY_PCT_CAP = new BigDecimal("1.0");
    private static final BigDecimal BPS_DENOMINATOR = new BigDecimal("10000");

    public SizingResult size(
            BigDecimal equityUsdt,
            BigDecimal configuredRiskPct,
            BigDecimal entryFill,
            BigDecimal slPrice,
            BigDecimal stepSize,
            BigDecimal minQty,
            BigDecimal feeBps) {

        if (equityUsdt == null || entryFill == null || slPrice == null) {
            return SizingResult.rejected("INVALID_INPUT");
        }

        BigDecimal riskPctEffective = normalizeRiskPct(configuredRiskPct);
        BigDecimal riskUsdt = equityUsdt
                .multiply(riskPctEffective)
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);

        BigDecimal stopDistance = entryFill.subtract(slPrice).abs();
        if (stopDistance.compareTo(BigDecimal.ZERO) <= 0) {
            return new SizingResult(false, riskPctEffective, riskUsdt, stopDistance,
                    BigDecimal.ZERO, BigDecimal.ZERO, "QTY_TOO_SMALL");
        }

        BigDecimal qtyRaw = riskUsdt.divide(stopDistance, 16, RoundingMode.HALF_DOWN);
        BigDecimal qtyRounded = roundDownToStep(qtyRaw, stepSize);

        if (qtyRounded.compareTo(BigDecimal.ZERO) <= 0) {
            return new SizingResult(false, riskPctEffective, riskUsdt, stopDistance,
                    qtyRounded, BigDecimal.ZERO, "QTY_TOO_SMALL");
        }

        if (minQty != null && minQty.compareTo(BigDecimal.ZERO) > 0 && qtyRounded.compareTo(minQty) < 0) {
            return new SizingResult(false, riskPctEffective, riskUsdt, stopDistance,
                    qtyRounded, BigDecimal.ZERO, "QTY_TOO_SMALL");
        }

        BigDecimal entryFee = qtyRounded
                .multiply(entryFill)
                .multiply(nullSafe(feeBps))
                .divide(BPS_DENOMINATOR, 8, RoundingMode.HALF_UP);

        return new SizingResult(true, riskPctEffective, riskUsdt, stopDistance,
                qtyRounded, entryFee, null);
    }

    public BigDecimal applyEntrySlippage(String side, BigDecimal mark, BigDecimal slippageBps) {
        BigDecimal factor = BigDecimal.ONE.add(
                (DemoPreflightMath.isLong(side) ? nullSafe(slippageBps) : nullSafe(slippageBps).negate())
                        .divide(BPS_DENOMINATOR, 10, RoundingMode.HALF_UP));
        return mark.multiply(factor);
    }

    public BigDecimal applyExitSlippage(String side, BigDecimal mark, BigDecimal slippageBps) {
        BigDecimal bps = nullSafe(slippageBps);
        BigDecimal factor = BigDecimal.ONE.add(
                (DemoPreflightMath.isLong(side) ? bps.negate() : bps)
                        .divide(BPS_DENOMINATOR, 10, RoundingMode.HALF_UP));
        return mark.multiply(factor);
    }

    public BigDecimal roundDownToStep(BigDecimal qty, BigDecimal stepSize) {
        if (qty == null) {
            return BigDecimal.ZERO;
        }
        if (stepSize == null || stepSize.compareTo(BigDecimal.ZERO) <= 0) {
            return qty;
        }
        return qty.divide(stepSize, 0, RoundingMode.DOWN).multiply(stepSize);
    }

    private BigDecimal normalizeRiskPct(BigDecimal configuredRiskPct) {
        BigDecimal riskPct = configuredRiskPct == null ? BigDecimal.ZERO : configuredRiskPct;
        if (riskPct.compareTo(BigDecimal.ZERO) < 0) {
            riskPct = BigDecimal.ZERO;
        }
        return riskPct.min(MAX_EQUITY_PCT_CAP);
    }

    private BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record SizingResult(
            boolean placeable,
            BigDecimal riskPctEffective,
            BigDecimal riskUsdt,
            BigDecimal stopDistance,
            BigDecimal qty,
            BigDecimal entryFee,
            String reasonCode) {

        static SizingResult rejected(String reasonCode) {
            return new SizingResult(false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, reasonCode);
        }
    }
}
