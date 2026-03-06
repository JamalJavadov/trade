package com.tradebot;

import com.tradebot.dto.Candle;
import com.tradebot.service.BiasDetector;
import com.tradebot.service.FractalDetector;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StrategyMathTest {

    @Test
    void testFractalDetector() {
        FractalDetector detector = new FractalDetector();
        List<Candle> candles = new ArrayList<>();
        // Creating a peak at index 2 (period 5 -> side 2)
        candles.add(createCandle("10", "5"));
        candles.add(createCandle("12", "6"));
        candles.add(createCandle("15", "7")); // HIGHEST
        candles.add(createCandle("11", "6"));
        candles.add(createCandle("10", "5"));

        int highIdx = detector.findLastSwingHighIndex(candles, 5);
        assertEquals(2, highIdx);
    }

    @Test
    void testFibonacciCalculator() {
        com.tradebot.service.FibonacciCalculator calc = new com.tradebot.service.FibonacciCalculator();

        // Bullish impulse from 100 to 200
        var levels = calc.calculate(new BigDecimal("100"), new BigDecimal("200"));

        assertEquals(0, new BigDecimal("200").compareTo(levels.zero()));
        assertEquals(0, new BigDecimal("100").compareTo(levels.hundred()));
        assertEquals(0, new BigDecimal("150").compareTo(levels.fifty())); // 200 - 100*0.5
        assertEquals(0, new BigDecimal("138.2").compareTo(levels.golden())); // 200 - 100*0.618
    }

    @Test
    void testRiskAndSizingCalculatorLimits() {
        com.tradebot.service.RiskAndSizingCalculator riskCalc = new com.tradebot.service.RiskAndSizingCalculator();
        com.tradebot.config.AppProperties props = new com.tradebot.config.AppProperties();
        props.getRisk().setEquityOverrideUsdt(new BigDecimal("10000")); // 10k equity
        props.getRisk().setMaxEquityPct(new BigDecimal("1.0")); // 1% risk = $100
        props.getBudget().setUsdt(new BigDecimal("10000"));

        com.tradebot.service.SweepReclaimDetector.Setup setup = new com.tradebot.service.SweepReclaimDetector.Setup(
                createCandle("100", "90"),
                createCandle("105", "95"),
                new BigDecimal("90"));

        com.tradebot.service.FibonacciCalculator.FibLevels levels = new com.tradebot.service.FibonacciCalculator.FibLevels(
                new BigDecimal("150"), new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("80"),
                new BigDecimal("160"), new BigDecimal("180"));

        com.tradebot.dto.StrategyTuningConfig tuningConfig = new com.tradebot.dto.StrategyTuningConfig();
        tuningConfig.getBuffers().setSlBufferPct(new BigDecimal("0.0005")); // 0.05%

        var plan = riskCalc.calculate(
                com.tradebot.service.BiasDetector.Bias.UPTREND,
                setup, levels,
                new BigDecimal("0.1"), new BigDecimal("0.001"), new BigDecimal("0.01"),
                props, tuningConfig);

        assertNotNull(plan);
        // Entry is reclaim close: 105
        // SL is sweep low 90 - 0.05% buffer (0.045) -> 89.955 -> rounded tick 0.1 ->
        // 90.0
        // wait, rounding to nearest tick 0.1. 89.955 -> 90.0
        assertEquals(new BigDecimal("105"), plan.entryPrice());
        assertEquals(new BigDecimal("90.0"), plan.slPrice());

        // Risk = $100. Stop Distance = 105 - 90 = 15.
        // Qty = 100 / 15 = 6.6666...
        // Rounded by step 0.001 -> 6.666
        assertEquals(new BigDecimal("6.666"), plan.quantity());
    }

    private Candle createCandle(String high, String low) {
        Candle c = new Candle();
        c.setHigh(new BigDecimal(high));
        c.setLow(new BigDecimal(low));
        c.setClose(new BigDecimal(high)); // mock
        return c;
    }
}
