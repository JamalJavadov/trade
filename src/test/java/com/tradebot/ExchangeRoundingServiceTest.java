package com.tradebot;

import com.tradebot.service.ExchangeRoundingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangeRoundingServiceTest {

    private final ExchangeRoundingService service = new ExchangeRoundingService();

    @Test
    void roundsQuantityDownToStepSize() {
        assertEquals(new BigDecimal("0.012"),
                service.roundQuantityDown(new BigDecimal("0.01234"), new BigDecimal("0.001")));
        assertNull(service.roundQuantityDown(new BigDecimal("0.0004"), new BigDecimal("0.001")));
    }

    @Test
    void roundsProtectionPricesTowardReferencePrice() {
        assertEquals(new BigDecimal("95.1"),
                service.roundPriceTowardReference(new BigDecimal("95.03"), new BigDecimal("100"), new BigDecimal("0.1")));
        assertEquals(new BigDecimal("104.9"),
                service.roundPriceTowardReference(new BigDecimal("104.96"), new BigDecimal("100"), new BigDecimal("0.1")));
    }

    @Test
    void detectsIncrementAlignment() {
        assertTrue(service.alignsToIncrement(new BigDecimal("0.015"), new BigDecimal("0.001")));
        assertFalse(service.alignsToIncrement(new BigDecimal("0.0154"), new BigDecimal("0.001")));
    }
}
