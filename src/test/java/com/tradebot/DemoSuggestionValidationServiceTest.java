package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.demo.dto.DemoTuningConfig;
import com.tradebot.demo.entity.DemoAiSuggestionItem;
import com.tradebot.demo.service.DemoSuggestionValidationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DemoSuggestionValidationServiceTest {

    @Test
    void mergeNormalizesWeightsAndKeepsTp3Constraint() {
        DemoSuggestionValidationService validation = new DemoSuggestionValidationService(new ObjectMapper());
        DemoTuningConfig active = new DemoTuningConfig();

        DemoAiSuggestionItem rr = item("rankingWeights.weightRR", "0.60");
        DemoAiSuggestionItem clean = item("rankingWeights.weightCleanSweep", "0.20");
        DemoAiSuggestionItem reclaim = item("rankingWeights.weightReclaimStrength", "0.10");
        DemoAiSuggestionItem liquidity = item("rankingWeights.weightLiquidity", "0.10");
        DemoAiSuggestionItem tp1 = item("management.partialTp1Pct", "0.50");
        DemoAiSuggestionItem tp2 = item("management.partialTp2Pct", "0.30");

        DemoTuningConfig merged = validation.applyAcceptedItems(
                active, List.of(rr, clean, reclaim, liquidity, tp1, tp2));

        BigDecimal sum = merged.getRankingWeights().getWeightRR()
                .add(merged.getRankingWeights().getWeightCleanSweep())
                .add(merged.getRankingWeights().getWeightReclaimStrength())
                .add(merged.getRankingWeights().getWeightLiquidity());

        assertEquals(0, sum.compareTo(new BigDecimal("1.0000")));
        assertEquals(0, merged.getPartialTp3Pct().compareTo(new BigDecimal("0.20")));
    }

    @Test
    void rejectsOutOfRangeProposal() {
        DemoSuggestionValidationService validation = new DemoSuggestionValidationService(new ObjectMapper());
        DemoTuningConfig active = new DemoTuningConfig();

        DemoAiSuggestionItem bad = item("management.timeStopMinutes", "500");
        assertThrows(IllegalArgumentException.class,
                () -> validation.applyAcceptedItems(active, List.of(bad)));
    }

    @Test
    void rejectsUnknownKey() {
        DemoSuggestionValidationService validation = new DemoSuggestionValidationService(new ObjectMapper());
        DemoTuningConfig active = new DemoTuningConfig();

        DemoAiSuggestionItem bad = item("risk.maxEquityPct", "0.2");
        assertThrows(IllegalArgumentException.class,
                () -> validation.applyAcceptedItems(active, List.of(bad)));
    }

    private DemoAiSuggestionItem item(String key, String value) {
        DemoAiSuggestionItem item = new DemoAiSuggestionItem();
        item.setKey(key);
        item.setProposedValue(value);
        return item;
    }
}
