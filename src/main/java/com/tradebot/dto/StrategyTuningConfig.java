package com.tradebot.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class StrategyTuningConfig {
    private Sweep sweep = new Sweep();
    private Buffers buffers = new Buffers();
    private Filters filters = new Filters();
    private Targets targets = new Targets();
    private RankingWeights rankingWeights = new RankingWeights();

    @Data
    public static class Sweep {
        private int enterZoneMaxCandles = 3; // [1..6]
        private int invalidBeyond618Candles = 5; // [3..10]
    }

    @Data
    public static class Buffers {
        private BigDecimal slBufferPct = new BigDecimal("0.0005"); // [0.0001..0.0030] (0.01% - 0.30%)
    }

    @Data
    public static class Filters {
        private boolean atrSpikeFilterEnabled = true;
        private BigDecimal atrSpikeMultiplier = new BigDecimal("3.0"); // [1.5..6.0]
    }

    @Data
    public static class Targets {
        private boolean tp2Enabled = true;
        private boolean tp3Enabled = true;
        private BigDecimal tp1Pct = new BigDecimal("0.50"); // [0.30..0.70]
        private BigDecimal tp2Pct = new BigDecimal("0.25"); // [0.10..0.40]
        // tp3Pct is computed: 1 - tp1Pct - tp2Pct (must be >= 0.10)

        public BigDecimal getTp3Pct() {
            BigDecimal sum = tp1Pct.add(tp2Pct);
            return BigDecimal.ONE.subtract(sum);
        }
    }

    @Data
    public static class RankingWeights {
        private BigDecimal weightRR = new BigDecimal("0.55"); // [0.30..0.80]
        private BigDecimal weightCleanSweep = new BigDecimal("0.20"); // [0.05..0.40]
        private BigDecimal weightReclaimStrength = new BigDecimal("0.15"); // [0.05..0.40]
        private BigDecimal weightLiquidity = new BigDecimal("0.10"); // [0.00..0.30]

        public void normalize() {
            BigDecimal sum = weightRR.add(weightCleanSweep).add(weightReclaimStrength).add(weightLiquidity);
            if (sum.compareTo(BigDecimal.ZERO) > 0 && sum.compareTo(BigDecimal.ONE) != 0) {
                weightRR = weightRR.divide(sum, 4, java.math.RoundingMode.HALF_UP);
                weightCleanSweep = weightCleanSweep.divide(sum, 4, java.math.RoundingMode.HALF_UP);
                weightReclaimStrength = weightReclaimStrength.divide(sum, 4, java.math.RoundingMode.HALF_UP);
                weightLiquidity = weightLiquidity.divide(sum, 4, java.math.RoundingMode.HALF_UP);
            }
        }
    }
}
