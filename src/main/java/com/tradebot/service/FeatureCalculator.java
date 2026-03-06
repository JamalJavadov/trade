package com.tradebot.service;

import com.tradebot.dto.Candle;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class FeatureCalculator {

    public BigDecimal calculateAtr(List<Candle> candles, int period) {
        if (candles == null || candles.size() <= period) {
            return BigDecimal.ZERO;
        }

        BigDecimal sumTr = BigDecimal.ZERO;
        for (int i = 1; i <= period; i++) {
            Candle current = candles.get(candles.size() - i);
            Candle previous = candles.get(candles.size() - i - 1);
            sumTr = sumTr.add(calculateTrueRange(current, previous));
        }
        return sumTr.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateTrueRange(Candle current, Candle previous) {
        BigDecimal highLow = current.getHigh().subtract(current.getLow());
        BigDecimal highClose = current.getHigh().subtract(previous.getClose()).abs();
        BigDecimal lowClose = current.getLow().subtract(previous.getClose()).abs();

        return highLow.max(highClose).max(lowClose);
    }

    public BigDecimal calculateRealizedVolatility(List<Candle> candles, int period) {
        if (candles == null || candles.size() <= period) {
            return BigDecimal.ZERO;
        }

        BigDecimal sumReturns = BigDecimal.ZERO;
        BigDecimal[] returns = new BigDecimal[period];

        for (int i = 0; i < period; i++) {
            Candle current = candles.get(candles.size() - period + i);
            Candle previous = candles.get(candles.size() - period + i - 1);

            if (previous.getClose().compareTo(BigDecimal.ZERO) == 0) {
                returns[i] = BigDecimal.ZERO;
            } else {
                returns[i] = current.getClose().subtract(previous.getClose())
                        .divide(previous.getClose(), 8, RoundingMode.HALF_UP);
            }
            sumReturns = sumReturns.add(returns[i]);
        }

        BigDecimal meanReturn = sumReturns.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
        BigDecimal sumSquaredDeviations = BigDecimal.ZERO;

        for (int i = 0; i < period; i++) {
            BigDecimal deviation = returns[i].subtract(meanReturn);
            sumSquaredDeviations = sumSquaredDeviations.add(deviation.multiply(deviation));
        }

        BigDecimal variance = sumSquaredDeviations.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
    }

    public BigDecimal calculateVolumeZScore(List<Candle> candles, int period) {
        if (candles == null || candles.size() < period) {
            return BigDecimal.ZERO;
        }

        BigDecimal sumVolume = BigDecimal.ZERO;
        for (int i = 1; i <= period; i++) {
            sumVolume = sumVolume.add(candles.get(candles.size() - i).getVolume());
        }

        BigDecimal meanVolume = sumVolume.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
        BigDecimal sumSquaredDeviations = BigDecimal.ZERO;

        for (int i = 1; i <= period; i++) {
            BigDecimal vol = candles.get(candles.size() - i).getVolume();
            BigDecimal deviation = vol.subtract(meanVolume);
            sumSquaredDeviations = sumSquaredDeviations.add(deviation.multiply(deviation));
        }

        BigDecimal variance = sumSquaredDeviations.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
        double stdDev = Math.sqrt(variance.doubleValue());

        if (stdDev == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal currentVol = candles.get(candles.size() - 1).getVolume();
        return currentVol.subtract(meanVolume).divide(BigDecimal.valueOf(stdDev), 8, RoundingMode.HALF_UP);
    }

    public BigDecimal calculateSma(List<Candle> candles, int period) {
        if (candles == null || candles.size() < period) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 1; i <= period; i++) {
            sum = sum.add(candles.get(candles.size() - i).getClose());
        }
        return sum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
    }
}
