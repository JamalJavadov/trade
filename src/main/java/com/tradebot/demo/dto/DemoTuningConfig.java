package com.tradebot.demo.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Data
public class DemoTuningConfig {

    private Sweep sweep = new Sweep();
    private Buffers buffers = new Buffers();
    private Filters filters = new Filters();
    private RankingWeights rankingWeights = new RankingWeights();
    private Management management = new Management();

    public void normalize() {
        if (rankingWeights == null) {
            rankingWeights = new RankingWeights();
        }
        rankingWeights.normalize();
    }

    @JsonIgnore
    public BigDecimal getPartialTp3Pct() {
        BigDecimal tp1 = management == null || management.getPartialTp1Pct() == null
                ? new BigDecimal("0.50")
                : management.getPartialTp1Pct();
        BigDecimal tp2 = management == null || management.getPartialTp2Pct() == null
                ? new BigDecimal("0.25")
                : management.getPartialTp2Pct();
        return BigDecimal.ONE.subtract(tp1.add(tp2));
    }

    @Data
    public static class Sweep {
        private int maxSweepCandles = 3;
        private int invalidBeyond618Candles = 5;
    }

    @Data
    public static class Buffers {
        private int slBufferTicks = 1;
    }

    @Data
    public static class Filters {
        private boolean atrSpikeFilterEnabled = true;
        private BigDecimal atrSpikeMultiplier = new BigDecimal("3.0");
        private BigDecimal minReclaimStrength = BigDecimal.ZERO;
    }

    @Data
    public static class RankingWeights {
        private BigDecimal weightRR = new BigDecimal("0.55");
        private BigDecimal weightCleanSweep = new BigDecimal("0.20");
        private BigDecimal weightReclaimStrength = new BigDecimal("0.15");
        private BigDecimal weightLiquidity = new BigDecimal("0.10");

        public void normalize() {
            BigDecimal rr = safe(weightRR);
            BigDecimal clean = safe(weightCleanSweep);
            BigDecimal reclaim = safe(weightReclaimStrength);
            BigDecimal liquidity = safe(weightLiquidity);
            BigDecimal sum = rr.add(clean).add(reclaim).add(liquidity);
            if (sum.compareTo(BigDecimal.ZERO) <= 0) {
                weightRR = new BigDecimal("0.55");
                weightCleanSweep = new BigDecimal("0.20");
                weightReclaimStrength = new BigDecimal("0.15");
                weightLiquidity = new BigDecimal("0.10");
                return;
            }
            weightRR = rr.divide(sum, 4, RoundingMode.HALF_UP);
            weightCleanSweep = clean.divide(sum, 4, RoundingMode.HALF_UP);
            weightReclaimStrength = reclaim.divide(sum, 4, RoundingMode.HALF_UP);
            weightLiquidity = liquidity.divide(sum, 4, RoundingMode.HALF_UP);
        }

        private BigDecimal safe(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
        }
    }

    @Data
    public static class Management {
        private BigDecimal partialTp1Pct = new BigDecimal("0.50");
        private BigDecimal partialTp2Pct = new BigDecimal("0.25");
        private int timeStopMinutes = 90;
    }
}
