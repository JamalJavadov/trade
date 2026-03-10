package com.tradebot.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class ExchangeRoundingService {

    public BigDecimal roundQuantityDown(BigDecimal value, BigDecimal stepSize) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        if (stepSize == null || stepSize.compareTo(BigDecimal.ZERO) <= 0) {
            return value.stripTrailingZeros();
        }
        BigDecimal rounded = value.divide(stepSize, 0, RoundingMode.DOWN).multiply(stepSize);
        return rounded.compareTo(BigDecimal.ZERO) <= 0 ? null : rounded.stripTrailingZeros();
    }

    public BigDecimal roundPriceTowardReference(BigDecimal value, BigDecimal referencePrice, BigDecimal tickSize) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        if (tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0) {
            return value.stripTrailingZeros();
        }
        if (referencePrice == null || referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
            return roundDown(value, tickSize);
        }
        if (value.compareTo(referencePrice) > 0) {
            return roundDown(value, tickSize);
        }
        if (value.compareTo(referencePrice) < 0) {
            return roundUp(value, tickSize);
        }
        return value.stripTrailingZeros();
    }

    public boolean alignsToIncrement(BigDecimal value, BigDecimal increment) {
        if (value == null || increment == null || increment.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        return roundDown(value, increment).compareTo(value) == 0;
    }

    private BigDecimal roundDown(BigDecimal value, BigDecimal increment) {
        BigDecimal rounded = value.divide(increment, 0, RoundingMode.DOWN).multiply(increment);
        return rounded.stripTrailingZeros();
    }

    private BigDecimal roundUp(BigDecimal value, BigDecimal increment) {
        BigDecimal rounded = value.divide(increment, 0, RoundingMode.UP).multiply(increment);
        return rounded.stripTrailingZeros();
    }
}
