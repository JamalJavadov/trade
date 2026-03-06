package com.tradebot;

import com.tradebot.dto.StrategyTuningConfig;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class AiSuggestionConfigTest {

    @Test
    void testWeightsNormalization() {
        StrategyTuningConfig config = new StrategyTuningConfig();
        // sum = 0.5 + 0.3 + 0.1 + 0.1 = 1.0 -> no changes
        config.getRankingWeights().setWeightRR(new BigDecimal("0.50"));
        config.getRankingWeights().setWeightCleanSweep(new BigDecimal("0.30"));
        config.getRankingWeights().setWeightReclaimStrength(new BigDecimal("0.10"));
        config.getRankingWeights().setWeightLiquidity(new BigDecimal("0.10"));

        config.getRankingWeights().normalize();
        assertEquals(new BigDecimal("0.50"), config.getRankingWeights().getWeightRR());

        // sum = 2.0 -> should halve them
        config.getRankingWeights().setWeightRR(new BigDecimal("1.0"));
        config.getRankingWeights().setWeightCleanSweep(new BigDecimal("0.60"));
        config.getRankingWeights().setWeightReclaimStrength(new BigDecimal("0.20"));
        config.getRankingWeights().setWeightLiquidity(new BigDecimal("0.20"));

        config.getRankingWeights().normalize();
        assertEquals(0, new BigDecimal("0.5000").compareTo(config.getRankingWeights().getWeightRR()));
        assertEquals(0, new BigDecimal("0.3000").compareTo(config.getRankingWeights().getWeightCleanSweep()));
    }

    @Test
    void testTpCalculations() {
        StrategyTuningConfig config = new StrategyTuningConfig();
        config.getTargets().setTp1Pct(new BigDecimal("0.50"));
        config.getTargets().setTp2Pct(new BigDecimal("0.40"));

        assertEquals(0, new BigDecimal("0.10").compareTo(config.getTargets().getTp3Pct()));
    }
}
