package com.tradebot.service.scan;

import com.tradebot.config.AppProperties;
import com.tradebot.dto.StrategyTuningConfig;
import com.tradebot.service.BiasDetector;
import com.tradebot.service.FibonacciCalculator;
import com.tradebot.service.ImpulseLegDetector;
import com.tradebot.service.RiskAndSizingCalculator;
import com.tradebot.service.SweepReclaimDetector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DeterministicStrategyEvaluator {

    private final BiasDetector biasDetector;
    private final ImpulseLegDetector impulseDetector;
    private final FibonacciCalculator fibCalculator;
    private final SweepReclaimDetector sweepDetector;
    private final RiskAndSizingCalculator riskCalculator;

    public DeterministicStrategyResult evaluate(
            FrozenSymbolSnapshot snapshot,
            AppProperties runtimeProperties,
            StrategyTuningConfig tuningConfig) {

        if (snapshot.executionCandles() == null || snapshot.biasCandles() == null
                || snapshot.executionCandles().size() < 50 || snapshot.biasCandles().size() < 50) {
            return noTrade("DATA_ERROR", "Insufficient candles after snapshot freeze.", null, null, null, null, Map.of());
        }

        String biasText = null;
        BiasDetector.Bias bias = biasDetector.detectBias(
                snapshot.biasCandles(),
                runtimeProperties.getStrategy().getFractalPeriod());
        biasText = bias != null ? bias.name() : null;
        if (bias == BiasDetector.Bias.UNKNOWN || bias == BiasDetector.Bias.RANGE) {
            return noTrade("NO_BIAS", "Bias is " + bias, biasText, null, null, null, Map.of("bias", biasText));
        }

        ImpulseLegDetector.ImpulseLeg impulse = impulseDetector.detectImpulseLeg(
                snapshot.executionCandles(),
                bias,
                runtimeProperties.getStrategy().getFractalPeriod());
        if (impulse == null) {
            return noTrade("NO_IMPULSE_BOS", "No impulse leg detected", biasText, null, null, null, Map.of("bias", biasText));
        }

        FibonacciCalculator.FibLevels levels = fibCalculator.calculate(
                BigDecimal.valueOf(impulse.startPrice()),
                BigDecimal.valueOf(impulse.endPrice()));

        SweepReclaimDetector.Setup setup = sweepDetector.detect(
                snapshot.executionCandles(),
                impulse.endIndex(),
                bias,
                levels,
                tuningConfig);
        if (setup == null) {
            return noTrade("NO_SWEEP", "No valid sweep/reclaim", biasText, impulse, levels, null, Map.of("bias", biasText));
        }

        BigDecimal tickSize = decimal(snapshot.exchangeMetadata().get("tickSize"));
        BigDecimal stepSize = decimal(snapshot.exchangeMetadata().get("stepSize"));
        BigDecimal minQty = decimal(snapshot.exchangeMetadata().get("minQty"));

        RiskAndSizingCalculator.EvaluationResult riskResult = riskCalculator.calculateWithReason(
                bias,
                setup,
                levels,
                tickSize,
                stepSize,
                minQty,
                runtimeProperties,
                tuningConfig);
        if (!riskResult.isValid()) {
            Map<String, Object> diagnostics = buildDiagnostics(biasText, impulse, levels, setup, null);
            Map<String, Object> intermediate = buildIntermediateSignals(biasText, impulse, levels, setup, null);
            return new DeterministicStrategyResult(
                    "NO_TRADE",
                    "NONE",
                    biasText,
                    riskResult.skipReasonCode(),
                    "Risk filter failed: " + riskResult.skipReasonCode(),
                    false,
                    impulse,
                    levels,
                    setup,
                    null,
                    Map.of("min_rr", runtimeProperties.getStrategy().getMinRr()),
                    diagnostics,
                    intermediate);
        }

        RiskAndSizingCalculator.ExecutionPlan plan = riskResult.plan();
        Map<String, Object> metrics = buildMetrics(snapshot, bias, plan, runtimeProperties);
        Map<String, Object> diagnostics = buildDiagnostics(biasText, impulse, levels, setup, plan);
        Map<String, Object> intermediate = buildIntermediateSignals(biasText, impulse, levels, setup, plan);

        return new DeterministicStrategyResult(
                "VALID",
                bias == BiasDetector.Bias.UPTREND ? "LONG" : "SHORT",
                biasText,
                null,
                null,
                true,
                impulse,
                levels,
                setup,
                plan,
                metrics,
                diagnostics,
                intermediate);
    }

    private DeterministicStrategyResult noTrade(
            String skipReasonCode,
            String skipReasonText,
            String bias,
            ImpulseLegDetector.ImpulseLeg impulse,
            FibonacciCalculator.FibLevels levels,
            SweepReclaimDetector.Setup setup,
            Map<String, Object> extraMetrics) {
        Map<String, Object> diagnostics = buildDiagnostics(bias, impulse, levels, setup, null);
        Map<String, Object> intermediate = buildIntermediateSignals(bias, impulse, levels, setup, null);
        return new DeterministicStrategyResult(
                "NO_TRADE",
                "NONE",
                bias,
                skipReasonCode,
                skipReasonText,
                false,
                impulse,
                levels,
                setup,
                null,
                extraMetrics,
                diagnostics,
                intermediate);
    }

    private Map<String, Object> buildMetrics(
            FrozenSymbolSnapshot snapshot,
            BiasDetector.Bias bias,
            RiskAndSizingCalculator.ExecutionPlan plan,
            AppProperties runtimeProperties) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("rr_tp1", plan.rrToTp1());
        metrics.put("final_score", plan.rrToTp1());
        metrics.put("confidence_score", plan.rrToTp1());
        metrics.put("entry", plan.entryPrice());
        metrics.put("sl", plan.slPrice());
        metrics.put("tp1", plan.tp1Price());
        metrics.put("tp2", plan.tp2Price());
        metrics.put("tp3", plan.tp3Price());
        metrics.put("quantity", plan.quantity());
        metrics.put("leverage", runtimeProperties.getLeverage());
        metrics.put("min_rr", runtimeProperties.getStrategy().getMinRr());
        metrics.put("bias", bias.name());
        metrics.put("quote_volume_usdt", snapshot.quoteVolumeUsdt());
        return metrics;
    }

    private Map<String, Object> buildDiagnostics(
            String bias,
            ImpulseLegDetector.ImpulseLeg impulse,
            FibonacciCalculator.FibLevels levels,
            SweepReclaimDetector.Setup setup,
            RiskAndSizingCalculator.ExecutionPlan plan) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("bias", bias);
        if (impulse != null) {
            diagnostics.put("impulseStartIndex", impulse.startIndex());
            diagnostics.put("impulseEndIndex", impulse.endIndex());
            diagnostics.put("impulseStartPrice", impulse.startPrice());
            diagnostics.put("impulseEndPrice", impulse.endPrice());
        }
        if (levels != null) {
            diagnostics.put("fibZero", levels.zero());
            diagnostics.put("fibFifty", levels.fifty());
            diagnostics.put("fibGolden", levels.golden());
            diagnostics.put("fibHundred", levels.hundred());
            diagnostics.put("fibExt1", levels.ext1());
            diagnostics.put("fibExt2", levels.ext2());
        }
        if (setup != null) {
            diagnostics.put("sweepPrice", setup.sweepPrice());
            diagnostics.put("reclaimClose", setup.reclaimCandle() != null ? setup.reclaimCandle().getClose() : null);
            diagnostics.put("reclaimOpen", setup.reclaimCandle() != null ? setup.reclaimCandle().getOpen() : null);
        }
        if (plan != null) {
            diagnostics.put("entry", plan.entryPrice());
            diagnostics.put("sl", plan.slPrice());
            diagnostics.put("tp1", plan.tp1Price());
            diagnostics.put("tp2", plan.tp2Price());
            diagnostics.put("tp3", plan.tp3Price());
        }
        return diagnostics;
    }

    private Map<String, Object> buildIntermediateSignals(
            String bias,
            ImpulseLegDetector.ImpulseLeg impulse,
            FibonacciCalculator.FibLevels levels,
            SweepReclaimDetector.Setup setup,
            RiskAndSizingCalculator.ExecutionPlan plan) {
        Map<String, Object> intermediate = new LinkedHashMap<>();
        intermediate.put("bias", bias);
        intermediate.put("impulseDetected", impulse != null);
        intermediate.put("fibCalculated", levels != null);
        intermediate.put("sweepDetected", setup != null);
        intermediate.put("executionPlanCreated", plan != null);
        if (setup != null && setup.reclaimCandle() != null) {
            intermediate.put("reclaimClose", setup.reclaimCandle().getClose());
        }
        return intermediate;
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }
}
