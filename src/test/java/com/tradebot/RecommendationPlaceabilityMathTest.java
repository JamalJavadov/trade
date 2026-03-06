package com.tradebot;

import com.tradebot.service.RecommendationPlaceabilityMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendationPlaceabilityMathTest {

    @Test
    void fixture_LONG_placeable() {
        RecommendationPlaceabilityMath.PlaceabilityResult result = RecommendationPlaceabilityMath.evaluate(
                "BUY",
                new BigDecimal("100.0"),
                new BigDecimal("0.1"),
                new BigDecimal("104.0"),
                new BigDecimal("98.0"),
                new BigDecimal("2.0"));

        assertTrue(result.placeable());
        assertEquals("TP > MARK > SL", result.inequalityRule());
        assertEquals("LONG requires TP >= MARK + 1 tick and SL <= MARK - 1 tick.", result.requiredInequality());
        assertEquals(new BigDecimal("2"), result.rrToTp1());
        assertTrue(result.tpOk());
        assertTrue(result.slOk());
        assertTrue(result.rrOk());
        assertNull(result.suggestedTp1Adjusted());
        assertNull(result.suggestedSlAdjusted());
    }

    @Test
    void fixture_LONG_notPlaceable_requiresTickGapSuggestion() {
        RecommendationPlaceabilityMath.PlaceabilityResult result = RecommendationPlaceabilityMath.evaluate(
                "BUY",
                new BigDecimal("100.0"),
                new BigDecimal("0.1"),
                new BigDecimal("100.05"),
                new BigDecimal("99.95"),
                new BigDecimal("2.0"));

        assertFalse(result.placeable());
        assertFalse(result.tpOk());
        assertFalse(result.slOk());
        assertFalse(result.rrOk());
        assertEquals(new BigDecimal("100.1"), result.suggestedTp1Adjusted());
        assertEquals(new BigDecimal("99.9"), result.suggestedSlAdjusted());
    }

    @Test
    void fixture_SHORT_placeable() {
        RecommendationPlaceabilityMath.PlaceabilityResult result = RecommendationPlaceabilityMath.evaluate(
                "SELL",
                new BigDecimal("100.0"),
                new BigDecimal("0.1"),
                new BigDecimal("96.0"),
                new BigDecimal("102.0"),
                new BigDecimal("2.0"));

        assertTrue(result.placeable());
        assertEquals("SL > MARK > TP", result.inequalityRule());
        assertEquals("SHORT requires SL >= MARK + 1 tick and TP <= MARK - 1 tick.", result.requiredInequality());
        assertEquals(new BigDecimal("2"), result.rrToTp1());
    }

    @Test
    void rrGuardBlocksWhenLiveRrDropsBelowMinimum() {
        RecommendationPlaceabilityMath.PlaceabilityResult result = RecommendationPlaceabilityMath.evaluate(
                "SELL",
                new BigDecimal("100.0"),
                new BigDecimal("0.1"),
                new BigDecimal("99.95"),
                new BigDecimal("100.05"),
                new BigDecimal("2.0"));

        assertFalse(result.placeable());
        assertEquals(new BigDecimal("1"), result.rrToTp1());
        assertFalse(result.rrOk());
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("below minimum required")));
    }
}
