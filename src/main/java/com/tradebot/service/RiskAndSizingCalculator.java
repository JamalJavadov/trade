package com.tradebot.service;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.tradebot.config.AppProperties;
import com.tradebot.dto.StrategyTuningConfig;

@Service
public class RiskAndSizingCalculator {

    public record ExecutionPlan(
            BigDecimal entryPrice,
            BigDecimal slPrice,
            BigDecimal tp1Price,
            BigDecimal tp2Price,
            BigDecimal tp3Price,
            BigDecimal quantity,
            BigDecimal rrToTp1) {
    }

    public record EvaluationResult(ExecutionPlan plan, String skipReasonCode) {
        public boolean isValid() {
            return plan != null;
        }
    }

    public EvaluationResult calculateWithReason(
            BiasDetector.Bias bias,
            SweepReclaimDetector.Setup setup,
            FibonacciCalculator.FibLevels levels,
            BigDecimal tickSize,
            BigDecimal stepSize,
            BigDecimal minQty,
            AppProperties properties,
            StrategyTuningConfig config) {

        BigDecimal entry = setup.reclaimCandle().getClose();
        BigDecimal slBufferPct = config.getBuffers().getSlBufferPct();

        BigDecimal sl;
        if (bias == BiasDetector.Bias.UPTREND) {
            BigDecimal buffer = setup.sweepPrice().multiply(slBufferPct);
            sl = setup.sweepPrice().subtract(buffer);
        } else {
            BigDecimal buffer = setup.sweepPrice().multiply(slBufferPct);
            sl = setup.sweepPrice().add(buffer);
        }

        if (tickSize != null && tickSize.compareTo(BigDecimal.ZERO) > 0) {
            sl = sl.divide(tickSize, 0, RoundingMode.HALF_UP).multiply(tickSize);
        }

        BigDecimal tp1 = levels.zero();
        BigDecimal tp2 = levels.ext1();
        BigDecimal tp3 = levels.ext2();

        if (tickSize != null && tickSize.compareTo(BigDecimal.ZERO) > 0) {
            tp1 = tp1.divide(tickSize, 0, RoundingMode.HALF_UP).multiply(tickSize);
            tp2 = tp2.divide(tickSize, 0, RoundingMode.HALF_UP).multiply(tickSize);
            tp3 = tp3.divide(tickSize, 0, RoundingMode.HALF_UP).multiply(tickSize);
        }

        BigDecimal riskUsdt;
        if (properties.getRisk().getEquityOverrideUsdt() != null) {
            riskUsdt = properties.getRisk().getEquityOverrideUsdt().multiply(
                    properties.getRisk().getMaxEquityPct()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_DOWN);
        } else {
            BigDecimal equityConstraint = properties.getBudget().getUsdt().multiply(
                    properties.getRisk().getMaxBudgetPct()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_DOWN);
            riskUsdt = Math.min(equityConstraint.doubleValue(), properties.getBudget().getUsdt().doubleValue()) > 0
                    ? equityConstraint
                    : BigDecimal.ZERO;
        }

        BigDecimal stopDistance = entry.subtract(sl).abs();
        if (stopDistance.compareTo(BigDecimal.ZERO) == 0) {
            return new EvaluationResult(null, "QTY_TOO_SMALL");
        }

        BigDecimal quantity = riskUsdt.divide(stopDistance, 8, RoundingMode.HALF_DOWN);

        if (stepSize != null && stepSize.compareTo(BigDecimal.ZERO) > 0) {
            quantity = quantity.divide(stepSize, 0, RoundingMode.DOWN).multiply(stepSize);
        }

        if (minQty != null && quantity.compareTo(minQty) < 0) {
            return new EvaluationResult(null, "QTY_TOO_SMALL");
        }

        BigDecimal notional = quantity.multiply(entry);
        BigDecimal marginUsed = notional.divide(BigDecimal.valueOf(properties.getLeverage()), 4, RoundingMode.HALF_UP);
        BigDecimal maxMargin = properties.getBudget().getUsdt().multiply(new BigDecimal("0.98"));

        if (marginUsed.compareTo(maxMargin) > 0) {
            quantity = maxMargin.multiply(BigDecimal.valueOf(properties.getLeverage()))
                    .divide(entry, 8, RoundingMode.HALF_DOWN);
            if (stepSize != null && stepSize.compareTo(BigDecimal.ZERO) > 0) {
                quantity = quantity.divide(stepSize, 0, RoundingMode.DOWN).multiply(stepSize);
            }
            if (minQty != null && quantity.compareTo(minQty) < 0) {
                return new EvaluationResult(null, "QTY_TOO_SMALL");
            }
        }

        BigDecimal reward = entry.subtract(tp1).abs();
        BigDecimal rrTp1 = reward.divide(stopDistance, 2, RoundingMode.HALF_DOWN);

        if (rrTp1.compareTo(properties.getStrategy().getMinRr()) < 0) {
            return new EvaluationResult(null, "RR_TOO_LOW");
        }

        return new EvaluationResult(new ExecutionPlan(entry, sl, tp1, tp2, tp3, quantity, rrTp1), null);
    }

    public ExecutionPlan calculate(
            BiasDetector.Bias bias,
            SweepReclaimDetector.Setup setup,
            FibonacciCalculator.FibLevels levels,
            BigDecimal tickSize,
            BigDecimal stepSize,
            BigDecimal minQty,
            AppProperties properties,
            StrategyTuningConfig config) {

        BigDecimal entry = setup.reclaimCandle().getClose();
        BigDecimal slBufferPct = config.getBuffers().getSlBufferPct();

        BigDecimal sl;
        if (bias == BiasDetector.Bias.UPTREND) {
            BigDecimal buffer = setup.sweepPrice().multiply(slBufferPct);
            sl = setup.sweepPrice().subtract(buffer);
        } else {
            BigDecimal buffer = setup.sweepPrice().multiply(slBufferPct);
            sl = setup.sweepPrice().add(buffer);
        }

        // Round SL to tickSize
        if (tickSize != null && tickSize.compareTo(BigDecimal.ZERO) > 0) {
            sl = sl.divide(tickSize, 0, RoundingMode.HALF_UP).multiply(tickSize);
        }

        // Determine targets
        BigDecimal tp1 = levels.zero(); // Prior swing high/low structure
        BigDecimal tp2 = levels.ext1(); // -0.272 ext
        BigDecimal tp3 = levels.ext2(); // -0.618 ext

        if (tickSize != null && tickSize.compareTo(BigDecimal.ZERO) > 0) {
            tp1 = tp1.divide(tickSize, 0, RoundingMode.HALF_UP).multiply(tickSize);
            tp2 = tp2.divide(tickSize, 0, RoundingMode.HALF_UP).multiply(tickSize);
            tp3 = tp3.divide(tickSize, 0, RoundingMode.HALF_UP).multiply(tickSize);
        }

        BigDecimal riskUsdt = null;
        if (properties.getRisk().getEquityOverrideUsdt() != null) {
            riskUsdt = properties.getRisk().getEquityOverrideUsdt().multiply(
                    properties.getRisk().getMaxEquityPct()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_DOWN);
        } else {
            BigDecimal equityConstraint = properties.getBudget().getUsdt().multiply(
                    properties.getRisk().getMaxBudgetPct()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_DOWN);
            riskUsdt = Math.min(equityConstraint.doubleValue(),
                    properties.getBudget().getUsdt().doubleValue()) > 0 ? equityConstraint : BigDecimal.ZERO;
        }

        BigDecimal stopDistance = entry.subtract(sl).abs();
        if (stopDistance.compareTo(BigDecimal.ZERO) == 0)
            return null;

        BigDecimal quantity = riskUsdt.divide(stopDistance, 8, RoundingMode.HALF_DOWN);

        // Step size rounding
        if (stepSize != null && stepSize.compareTo(BigDecimal.ZERO) > 0) {
            quantity = quantity.divide(stepSize, 0, RoundingMode.DOWN).multiply(stepSize);
        }

        if (minQty != null && quantity.compareTo(minQty) < 0) {
            return null; // Violates min qty
        }

        // Check max margin limit constraint
        BigDecimal notional = quantity.multiply(entry);
        BigDecimal marginUsed = notional.divide(BigDecimal.valueOf(properties.getLeverage()), 4, RoundingMode.HALF_UP);
        BigDecimal maxMargin = properties.getBudget().getUsdt().multiply(new BigDecimal("0.98")); // 98%

        if (marginUsed.compareTo(maxMargin) > 0) {
            // Need to reduce quantity
            quantity = maxMargin.multiply(BigDecimal.valueOf(properties.getLeverage()))
                    .divide(entry, 8, RoundingMode.HALF_DOWN);
            if (stepSize != null && stepSize.compareTo(BigDecimal.ZERO) > 0) {
                quantity = quantity.divide(stepSize, 0, RoundingMode.DOWN).multiply(stepSize);
            }
            if (minQty != null && quantity.compareTo(minQty) < 0) {
                return null;
            }
        }

        // RR Check
        BigDecimal reward = entry.subtract(tp1).abs();
        BigDecimal rrTp1 = reward.divide(stopDistance, 2, RoundingMode.HALF_DOWN);

        if (rrTp1.compareTo(properties.getStrategy().getMinRr()) < 0) {
            return null; // Less than minimum RR
        }

        return new ExecutionPlan(entry, sl, tp1, tp2, tp3, quantity, rrTp1);
    }
}
