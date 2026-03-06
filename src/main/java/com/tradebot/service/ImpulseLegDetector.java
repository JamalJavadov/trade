package com.tradebot.service;

import com.tradebot.dto.Candle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ImpulseLegDetector {

    private final FractalDetector fractalDetector;

    public record ImpulseLeg(int startIndex, int endIndex, double startPrice, double endPrice,
            BiasDetector.Bias direction) {
    }

    public ImpulseLeg detectImpulseLeg(List<Candle> execCandles, BiasDetector.Bias bias, int fractalPeriod) {
        if (bias == BiasDetector.Bias.UNKNOWN || bias == BiasDetector.Bias.RANGE) {
            return null; // Cannot trade without trend
        }

        int lastHighIdx = fractalDetector.findLastSwingHighIndex(execCandles, fractalPeriod);
        int lastLowIdx = fractalDetector.findLastSwingLowIndex(execCandles, fractalPeriod);

        if (lastHighIdx == -1 || lastLowIdx == -1)
            return null;

        if (bias == BiasDetector.Bias.UPTREND) {
            // Looking for Bullish Impulse (BOS above last high)
            // The impulse starts at a low and goes to a High
            if (lastHighIdx > lastLowIdx) {
                // The most recent swing is a HIGH, meaning we had an impulse up from the
                // previous low.
                double startPrice = execCandles.get(lastLowIdx).getLow().doubleValue();
                double endPrice = execCandles.get(lastHighIdx).getHigh().doubleValue();
                return new ImpulseLeg(lastLowIdx, lastHighIdx, startPrice, endPrice, bias);
            }
        } else if (bias == BiasDetector.Bias.DOWNTREND) {
            // Looking for Bearish Impulse
            if (lastLowIdx > lastHighIdx) {
                // The most recent swing is a LOW, meaning we had an impulse down from the
                // previous high.
                double startPrice = execCandles.get(lastHighIdx).getHigh().doubleValue();
                double endPrice = execCandles.get(lastLowIdx).getLow().doubleValue();
                return new ImpulseLeg(lastHighIdx, lastLowIdx, startPrice, endPrice, bias);
            }
        }

        return null;
    }
}
