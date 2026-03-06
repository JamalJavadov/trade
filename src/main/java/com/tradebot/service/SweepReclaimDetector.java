package com.tradebot.service;

import com.tradebot.dto.Candle;
import org.springframework.stereotype.Service;
import java.util.List;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import com.tradebot.dto.StrategyTuningConfig;

@Service
@RequiredArgsConstructor
public class SweepReclaimDetector {

    public record Setup(Candle sweepCandle, Candle reclaimCandle, BigDecimal sweepPrice) {
    }

    public Setup detect(List<Candle> candles, int startIndex, BiasDetector.Bias direction,
            FibonacciCalculator.FibLevels levels, StrategyTuningConfig config) {

        int sweepCount = 0;
        int beyondGoldenCount = 0;

        Candle sweepCandle = null;
        BigDecimal sweepExtreme = null;

        for (int i = startIndex; i < candles.size(); i++) {
            Candle current = candles.get(i);
            boolean inZone = false;
            boolean swept = false;
            boolean invalid = false;
            boolean reclaimed = false;

            if (direction == BiasDetector.Bias.UPTREND) {
                // Golden zone is DISCOUNT: levels.fifty -> levels.golden
                // where golden is lower value than fifty.

                if (current.getLow().compareTo(levels.golden()) <= 0) {
                    swept = true;
                    if (sweepExtreme == null || current.getLow().compareTo(sweepExtreme) < 0) {
                        sweepExtreme = current.getLow();
                        sweepCandle = current;
                    }
                }

                // If it stays below golden for too long, invalidate
                if (current.getClose().compareTo(levels.golden()) < 0) {
                    beyondGoldenCount++;
                } else {
                    beyondGoldenCount = 0; // reset
                }

                if (swept && current.getClose().compareTo(levels.golden()) > 0
                        && current.getClose().compareTo(levels.fifty()) <= 0) {
                    reclaimed = true;
                }

            } else if (direction == BiasDetector.Bias.DOWNTREND) {
                // Golden zone is PREMIUM: fifty -> golden
                // where golden is higher value than fifty.

                if (current.getHigh().compareTo(levels.golden()) >= 0) {
                    swept = true;
                    if (sweepExtreme == null || current.getHigh().compareTo(sweepExtreme) > 0) {
                        sweepExtreme = current.getHigh();
                        sweepCandle = current;
                    }
                }

                if (current.getClose().compareTo(levels.golden()) > 0) {
                    beyondGoldenCount++;
                } else {
                    beyondGoldenCount = 0;
                }

                if (swept && current.getClose().compareTo(levels.golden()) < 0
                        && current.getClose().compareTo(levels.fifty()) >= 0) {
                    reclaimed = true;
                }
            }

            if (swept) {
                sweepCount++;
            }

            if (beyondGoldenCount >= config.getSweep().getInvalidBeyond618Candles()) {
                return null; // Invalidated
            }

            if (reclaimed && sweepCandle != null) {
                // Reclaimed into the zone after a sweep.
                if (sweepCount > 0 && sweepCount <= config.getSweep().getEnterZoneMaxCandles()) {
                    return new Setup(sweepCandle, current, sweepExtreme);
                }
                // If reclaimed but it took too many candles sweeping, it's invalid
                return null;
            }
        }

        return null;
    }
}
