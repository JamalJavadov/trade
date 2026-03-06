package com.tradebot.demo.service;

import com.tradebot.demo.dto.DemoTuningConfig;
import com.tradebot.dto.Candle;
import com.tradebot.dto.StrategyTuningConfig;
import com.tradebot.service.BiasDetector;
import com.tradebot.service.FeatureCalculator;
import com.tradebot.service.FibonacciCalculator;
import com.tradebot.service.ImpulseLegDetector;
import com.tradebot.service.SweepReclaimDetector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DemoCandidateEvaluator {

    public static final int LOCKED_FRACTAL_PERIOD = 5;
    public static final BigDecimal LOCKED_MIN_RR = new BigDecimal("2.0");

    private final BiasDetector biasDetector;
    private final ImpulseLegDetector impulseLegDetector;
    private final FibonacciCalculator fibonacciCalculator;
    private final SweepReclaimDetector sweepReclaimDetector;
    private final FeatureCalculator featureCalculator;

    public CandidateResult evaluateSymbol(
            String symbol,
            BigDecimal quoteVolume,
            List<Candle> executionCandles,
            List<Candle> biasCandles,
            DemoMarketDataService.SymbolFilters filters,
            DemoTuningConfig config) {

        if (executionCandles == null || biasCandles == null
                || executionCandles.size() < 80 || biasCandles.size() < 80) {
            return null;
        }

        BiasDetector.Bias bias = biasDetector.detectBias(biasCandles, LOCKED_FRACTAL_PERIOD);
        if (bias == BiasDetector.Bias.UNKNOWN || bias == BiasDetector.Bias.RANGE) {
            return null;
        }

        ImpulseLegDetector.ImpulseLeg impulse = impulseLegDetector.detectImpulseLeg(
                executionCandles,
                bias,
                LOCKED_FRACTAL_PERIOD);
        if (impulse == null) {
            return null;
        }

        BigDecimal atrShort = featureCalculator.calculateAtr(executionCandles, 14);
        BigDecimal atrLong = featureCalculator.calculateAtr(executionCandles, 50);
        BigDecimal atrRatio = atrLong.compareTo(BigDecimal.ZERO) > 0
                ? atrShort.divide(atrLong, 6, RoundingMode.HALF_UP)
                : BigDecimal.ONE;
        if (config.getFilters().isAtrSpikeFilterEnabled()
                && atrRatio.compareTo(config.getFilters().getAtrSpikeMultiplier()) > 0) {
            return null;
        }

        FibonacciCalculator.FibLevels fibLevels = fibonacciCalculator.calculate(
                BigDecimal.valueOf(impulse.startPrice()),
                BigDecimal.valueOf(impulse.endPrice()));

        SweepReclaimDetector.Setup setup = sweepReclaimDetector.detect(
                executionCandles,
                impulse.endIndex(),
                bias,
                fibLevels,
                toSweepCompatConfig(config));
        if (setup == null) {
            return null;
        }

        BigDecimal reclaimStrength = reclaimStrength(setup.reclaimCandle());
        if (reclaimStrength.compareTo(config.getFilters().getMinReclaimStrength()) < 0) {
            return null;
        }

        BigDecimal entry = setup.reclaimCandle().getClose();
        BigDecimal sl = computeStopLoss(bias, setup, config, filters.tickSize());
        BigDecimal tp1 = roundToTick(fibLevels.zero(), filters.tickSize());
        BigDecimal tp2 = roundToTick(fibLevels.ext1(), filters.tickSize());
        BigDecimal tp3 = roundToTick(fibLevels.ext2(), filters.tickSize());

        BigDecimal stopDistance = entry.subtract(sl).abs();
        if (stopDistance.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        BigDecimal rrToTp1 = tp1.subtract(entry).abs().divide(stopDistance, 6, RoundingMode.HALF_UP);
        if (rrToTp1.compareTo(LOCKED_MIN_RR) < 0) {
            return null;
        }

        BigDecimal sweepDepthRatio = sweepDepthRatio(bias, setup.sweepPrice(), fibLevels);
        BigDecimal cleanSweepScore = BigDecimal.ONE.subtract(sweepDepthRatio).max(BigDecimal.ZERO);
        BigDecimal liquidityScore = liquidityScore(quoteVolume);

        BigDecimal score = weightedScore(config, rrToTp1, cleanSweepScore, reclaimStrength, liquidityScore);
        BigDecimal confidence = score;

        String side = bias == BiasDetector.Bias.UPTREND ? "LONG" : "SHORT";

        Map<String, Object> diagnostics = new HashMap<>();
        diagnostics.put("bias", bias.name());
        diagnostics.put("impulseStartIndex", impulse.startIndex());
        diagnostics.put("impulseEndIndex", impulse.endIndex());
        diagnostics.put("impulseStartPrice", impulse.startPrice());
        diagnostics.put("impulseEndPrice", impulse.endPrice());
        diagnostics.put("sweepPrice", setup.sweepPrice());
        diagnostics.put("entry", entry);
        diagnostics.put("sl", sl);
        diagnostics.put("tp1", tp1);
        diagnostics.put("tp2", tp2);
        diagnostics.put("tp3", tp3);
        diagnostics.put("rrToTp1", rrToTp1);
        diagnostics.put("reclaimStrength", reclaimStrength);
        diagnostics.put("sweepDepthRatio", sweepDepthRatio);
        diagnostics.put("atrRatio", atrRatio);
        diagnostics.put("liquidityScore", liquidityScore);
        diagnostics.put("cleanSweepScore", cleanSweepScore);
        diagnostics.put("staleSetup", false);

        return new CandidateResult(
                symbol,
                side,
                entry,
                sl,
                tp1,
                tp2,
                tp3,
                rrToTp1,
                confidence,
                score,
                quoteVolume,
                diagnostics);
    }

    private StrategyTuningConfig toSweepCompatConfig(DemoTuningConfig config) {
        StrategyTuningConfig compat = new StrategyTuningConfig();
        compat.getSweep().setEnterZoneMaxCandles(config.getSweep().getMaxSweepCandles());
        compat.getSweep().setInvalidBeyond618Candles(config.getSweep().getInvalidBeyond618Candles());
        return compat;
    }

    private BigDecimal weightedScore(
            DemoTuningConfig config,
            BigDecimal rrToTp1,
            BigDecimal cleanSweepScore,
            BigDecimal reclaimStrength,
            BigDecimal liquidityScore) {

        BigDecimal rrScore = rrToTp1.divide(new BigDecimal("4.0"), 6, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE)
                .max(BigDecimal.ZERO);

        return rrScore.multiply(config.getRankingWeights().getWeightRR())
                .add(cleanSweepScore.multiply(config.getRankingWeights().getWeightCleanSweep()))
                .add(reclaimStrength.multiply(config.getRankingWeights().getWeightReclaimStrength()))
                .add(liquidityScore.multiply(config.getRankingWeights().getWeightLiquidity()));
    }

    private BigDecimal liquidityScore(BigDecimal quoteVolume) {
        if (quoteVolume == null || quoteVolume.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        double normalized = Math.log10(quoteVolume.doubleValue() + 1.0d) / 9.0d;
        if (normalized < 0d) {
            normalized = 0d;
        }
        if (normalized > 1d) {
            normalized = 1d;
        }
        return BigDecimal.valueOf(normalized).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal sweepDepthRatio(
            BiasDetector.Bias bias,
            BigDecimal sweepPrice,
            FibonacciCalculator.FibLevels fibLevels) {
        BigDecimal zoneHeight = fibLevels.fifty().subtract(fibLevels.golden()).abs();
        if (zoneHeight.compareTo(BigDecimal.ZERO) <= 0 || sweepPrice == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal depth;
        if (bias == BiasDetector.Bias.UPTREND) {
            depth = fibLevels.golden().subtract(sweepPrice).max(BigDecimal.ZERO);
        } else {
            depth = sweepPrice.subtract(fibLevels.golden()).max(BigDecimal.ZERO);
        }
        BigDecimal ratio = depth.divide(zoneHeight, 6, RoundingMode.HALF_UP);
        if (ratio.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return ratio.max(BigDecimal.ZERO);
    }

    private BigDecimal reclaimStrength(Candle reclaimCandle) {
        if (reclaimCandle == null || reclaimCandle.getHigh() == null || reclaimCandle.getLow() == null
                || reclaimCandle.getOpen() == null || reclaimCandle.getClose() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal range = reclaimCandle.getHigh().subtract(reclaimCandle.getLow()).abs();
        if (range.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal body = reclaimCandle.getClose().subtract(reclaimCandle.getOpen()).abs();
        BigDecimal strength = body.divide(range, 6, RoundingMode.HALF_UP);
        if (strength.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return strength.max(BigDecimal.ZERO);
    }

    private BigDecimal computeStopLoss(
            BiasDetector.Bias bias,
            SweepReclaimDetector.Setup setup,
            DemoTuningConfig config,
            BigDecimal tickSize) {

        BigDecimal tick = tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : tickSize;
        BigDecimal buffer = tick.multiply(BigDecimal.valueOf(Math.max(config.getBuffers().getSlBufferTicks(), 0L)));
        BigDecimal raw = bias == BiasDetector.Bias.UPTREND
                ? setup.sweepPrice().subtract(buffer)
                : setup.sweepPrice().add(buffer);
        return roundToTick(raw, tickSize);
    }

    private BigDecimal roundToTick(BigDecimal value, BigDecimal tickSize) {
        if (value == null || tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0) {
            return value;
        }
        return value.divide(tickSize, 0, RoundingMode.HALF_UP).multiply(tickSize);
    }

    public record CandidateResult(
            String symbol,
            String side,
            BigDecimal entry,
            BigDecimal sl,
            BigDecimal tp1,
            BigDecimal tp2,
            BigDecimal tp3,
            BigDecimal rrToTp1,
            BigDecimal confidence,
            BigDecimal score,
            BigDecimal quoteVolume,
            Map<String, Object> diagnosticsSnapshot) {
    }
}
