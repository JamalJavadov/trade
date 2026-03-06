package com.tradebot;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.RoundingMode;
import static org.junit.jupiter.api.Assertions.*;

public class DirectionConstraintsTest {

    private boolean validateLong(BigDecimal tp, BigDecimal sl, BigDecimal mark) {
        return tp.compareTo(mark) > 0 && sl.compareTo(mark) < 0;
    }

    private boolean validateShort(BigDecimal tp, BigDecimal sl, BigDecimal mark) {
        return tp.compareTo(mark) < 0 && sl.compareTo(mark) > 0;
    }

    @Test
    void testLongDirectionConstraints() {
        BigDecimal mark = new BigDecimal("100.0");

        // Valid long
        assertTrue(validateLong(new BigDecimal("105.0"), new BigDecimal("95.0"), mark));

        // Invalid: tp <= mark
        assertFalse(validateLong(new BigDecimal("100.0"), new BigDecimal("95.0"), mark));
        assertFalse(validateLong(new BigDecimal("99.0"), new BigDecimal("95.0"), mark));

        // Invalid: sl >= mark
        assertFalse(validateLong(new BigDecimal("105.0"), new BigDecimal("100.0"), mark));
        assertFalse(validateLong(new BigDecimal("105.0"), new BigDecimal("101.0"), mark));
    }

    @Test
    void testShortDirectionConstraints() {
        BigDecimal mark = new BigDecimal("100.0");

        // Valid short
        assertTrue(validateShort(new BigDecimal("95.0"), new BigDecimal("105.0"), mark));

        // Invalid: tp >= mark
        assertFalse(validateShort(new BigDecimal("100.0"), new BigDecimal("105.0"), mark));
        assertFalse(validateShort(new BigDecimal("101.0"), new BigDecimal("105.0"), mark));

        // Invalid: sl <= mark
        assertFalse(validateShort(new BigDecimal("95.0"), new BigDecimal("100.0"), mark));
        assertFalse(validateShort(new BigDecimal("95.0"), new BigDecimal("99.0"), mark));
    }

    @Test
    void testTickSizeRoundingBufferMath() {
        // Demonstrate how a calculated TP/SL price with tickSize buffer maintains
        // inequality
        BigDecimal rawSlLong = new BigDecimal("99.999");
        BigDecimal mark = new BigDecimal("100.00");
        BigDecimal tickSize = new BigDecimal("0.1"); // Aggressive tick size

        // Round using Half-Up exactly like RiskAndSizingCalculator
        BigDecimal roundedSlLong = rawSlLong.divide(tickSize, 0, RoundingMode.HALF_UP).multiply(tickSize);
        // 99.999 / 0.1 = 999.99 -> Half-up rounds to 1000. 1000 * 0.1 = 100.0

        // In this edge case, rounded SL equals Mark! This is a Binance violation for
        // Long SL (sl < mark needed).
        // This test proves we must guarantee strict inequality after rounding.
        assertFalse(validateLong(new BigDecimal("105.0"), roundedSlLong, mark));

        // If we apply a minimum tick buffer to ensure inequality holds:
        BigDecimal safeSlLong = roundedSlLong;
        if (safeSlLong.compareTo(mark) >= 0) {
            safeSlLong = safeSlLong.subtract(tickSize); // push away from mark
        }

        assertTrue(validateLong(new BigDecimal("105.0"), safeSlLong, mark));
        assertEquals(new BigDecimal("99.9"), safeSlLong);
    }
}
