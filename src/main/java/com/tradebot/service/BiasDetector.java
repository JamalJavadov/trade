package com.tradebot.service;

import com.tradebot.dto.Candle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BiasDetector {

    private final FractalDetector fractalDetector;

    public enum Bias {
        UPTREND, DOWNTREND, RANGE, UNKNOWN
    }

    public Bias detectBias(List<Candle> biasCandles, int fractalPeriod) {
        if (biasCandles == null || biasCandles.size() < fractalPeriod * 4) {
            return Bias.UNKNOWN;
        }

        int lastHighIdx = fractalDetector.findLastSwingHighIndex(biasCandles, fractalPeriod);
        if (lastHighIdx == -1)
            return Bias.UNKNOWN;

        int prevHighIdx = findSwingHighBefore(biasCandles, fractalPeriod, lastHighIdx);
        if (prevHighIdx == -1)
            return Bias.UNKNOWN;

        int lastLowIdx = fractalDetector.findLastSwingLowIndex(biasCandles, fractalPeriod);
        if (lastLowIdx == -1)
            return Bias.UNKNOWN;

        int prevLowIdx = findSwingLowBefore(biasCandles, fractalPeriod, lastLowIdx);
        if (prevLowIdx == -1)
            return Bias.UNKNOWN;

        double h1 = biasCandles.get(lastHighIdx).getHigh().doubleValue();
        double h2 = biasCandles.get(prevHighIdx).getHigh().doubleValue();

        double l1 = biasCandles.get(lastLowIdx).getLow().doubleValue();
        double l2 = biasCandles.get(prevLowIdx).getLow().doubleValue();

        boolean higherHighs = h1 > h2;
        boolean higherLows = l1 > l2;

        boolean lowerHighs = h1 < h2;
        boolean lowerLows = l1 < l2;

        if (higherHighs && higherLows) {
            return Bias.UPTREND;
        } else if (lowerHighs && lowerLows) {
            return Bias.DOWNTREND;
        }

        return Bias.RANGE;
    }

    private int findSwingHighBefore(List<Candle> candles, int period, int beforeIndex) {
        int side = period / 2;
        for (int i = beforeIndex - side - 1; i >= side; i--) {
            // Need a private method check, or we can just sublist. Let's sublist for
            // simplicity of reuse.
            // Wait, we can't easily sublist without breaking index mapping.
            // Better to implement a local check or expose isFractalHigh.
            if (isFractalHigh(candles, i, side)) {
                return i; // Found the previous high
            }
        }
        return -1;
    }

    private int findSwingLowBefore(List<Candle> candles, int period, int beforeIndex) {
        int side = period / 2;
        for (int i = beforeIndex - side - 1; i >= side; i--) {
            if (isFractalLow(candles, i, side)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isFractalHigh(List<Candle> candles, int index, int side) {
        double currentHigh = candles.get(index).getHigh().doubleValue();
        for (int j = 1; j <= side; j++) {
            if (candles.get(index - j).getHigh().doubleValue() >= currentHigh ||
                    candles.get(index + j).getHigh().doubleValue() >= currentHigh) {
                return false;
            }
        }
        return true;
    }

    private boolean isFractalLow(List<Candle> candles, int index, int side) {
        double currentLow = candles.get(index).getLow().doubleValue();
        for (int j = 1; j <= side; j++) {
            if (candles.get(index - j).getLow().doubleValue() <= currentLow ||
                    candles.get(index + j).getLow().doubleValue() <= currentLow) {
                return false;
            }
        }
        return true;
    }
}
