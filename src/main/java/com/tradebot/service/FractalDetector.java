package com.tradebot.service;

import com.tradebot.dto.Candle;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class FractalDetector {

    public enum FractalType {
        HIGH, LOW, NONE
    }

    public record Fractal(int index, FractalType type) {
    }

    /**
     * Finds the index of the last confirmed swing high based on Williams Fractal
     * (default period 5).
     * Meaning: High of candle[i] > High of previous 2 and next 2.
     */
    public int findLastSwingHighIndex(List<Candle> candles, int period) {
        int side = period / 2;
        for (int i = candles.size() - 1 - side; i >= side; i--) {
            if (isFractalHigh(candles, i, side)) {
                return i;
            }
        }
        return -1;
    }

    public int findLastSwingLowIndex(List<Candle> candles, int period) {
        int side = period / 2;
        for (int i = candles.size() - 1 - side; i >= side; i--) {
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
