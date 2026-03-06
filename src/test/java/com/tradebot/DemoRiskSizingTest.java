package com.tradebot;

import com.tradebot.demo.service.DemoRiskSizer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoRiskSizingTest {

    private final DemoRiskSizer sizer = new DemoRiskSizer();

    @Test
    void riskCapIsHardLimitedToOnePercent() {
        DemoRiskSizer.SizingResult result = sizer.size(
                new BigDecimal("1000"),
                new BigDecimal("3.0"),
                new BigDecimal("100"),
                new BigDecimal("99"),
                new BigDecimal("0.001"),
                new BigDecimal("0.001"),
                new BigDecimal("4"));

        assertTrue(result.placeable());
        assertEquals(new BigDecimal("1.0"), result.riskPctEffective());
        assertEquals(new BigDecimal("10.00000000"), result.riskUsdt());
        assertEquals(new BigDecimal("10.000"), result.qty());
    }

    @Test
    void quantityIsRoundedDownToStepSize() {
        DemoRiskSizer.SizingResult result = sizer.size(
                new BigDecimal("1000"),
                new BigDecimal("0.5"),
                new BigDecimal("100"),
                new BigDecimal("99.1"),
                new BigDecimal("0.1"),
                new BigDecimal("0.1"),
                new BigDecimal("4"));

        assertTrue(result.placeable());
        assertEquals(new BigDecimal("5.5"), result.qty());
    }

    @Test
    void tooSmallQuantityIsRejected() {
        DemoRiskSizer.SizingResult result = sizer.size(
                new BigDecimal("100"),
                new BigDecimal("0.5"),
                new BigDecimal("100"),
                new BigDecimal("99.5"),
                new BigDecimal("0.1"),
                new BigDecimal("5"),
                new BigDecimal("4"));

        assertFalse(result.placeable());
        assertEquals("QTY_TOO_SMALL", result.reasonCode());
    }
}
