package com.tradebot;

import com.tradebot.demo.service.DemoPreflightMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoPlaceabilityPreflightTest {

    @Test
    void longStrictInequalityRequiresTickGap() {
        DemoPreflightMath.PreflightResult pass = DemoPreflightMath.evaluate(
                "LONG",
                new BigDecimal("100"),
                new BigDecimal("0.1"),
                new BigDecimal("100.2"),
                new BigDecimal("99.9"),
                new BigDecimal("2.0"));

        assertTrue(pass.placeable());

        DemoPreflightMath.PreflightResult fail = DemoPreflightMath.evaluate(
                "LONG",
                new BigDecimal("100"),
                new BigDecimal("0.1"),
                new BigDecimal("100.05"),
                new BigDecimal("99.9"),
                new BigDecimal("2.0"));

        assertFalse(fail.placeable());
        assertEquals("PRICE_NEEDS_TICK_GAP", fail.reasonCode());
    }

    @Test
    void shortStrictInequalityRequiresTickGap() {
        DemoPreflightMath.PreflightResult pass = DemoPreflightMath.evaluate(
                "SHORT",
                new BigDecimal("100"),
                new BigDecimal("0.1"),
                new BigDecimal("99.8"),
                new BigDecimal("100.1"),
                new BigDecimal("2.0"));

        assertTrue(pass.placeable());

        DemoPreflightMath.PreflightResult fail = DemoPreflightMath.evaluate(
                "SHORT",
                new BigDecimal("100"),
                new BigDecimal("0.1"),
                new BigDecimal("99.95"),
                new BigDecimal("100.1"),
                new BigDecimal("2.0"));

        assertFalse(fail.placeable());
        assertEquals("PRICE_NEEDS_TICK_GAP", fail.reasonCode());
    }

    @Test
    void rrLiveUsesLiveMarkAsEntryReference() {
        BigDecimal rr = DemoPreflightMath.computeRrToTp1(
                new BigDecimal("100"),
                new BigDecimal("104"),
                new BigDecimal("98"));

        assertEquals(new BigDecimal("2"), rr);

        DemoPreflightMath.PreflightResult fail = DemoPreflightMath.evaluate(
                "LONG",
                new BigDecimal("100"),
                new BigDecimal("0.1"),
                new BigDecimal("101"),
                new BigDecimal("99"),
                new BigDecimal("2.0"));

        assertFalse(fail.placeable());
        assertEquals("RR_BELOW_2", fail.reasonCode());
    }
}
