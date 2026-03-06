package com.tradebot.service;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class FibonacciCalculator {

    public record FibLevels(BigDecimal zero, BigDecimal fifty, BigDecimal golden, BigDecimal hundred, BigDecimal ext1,
            BigDecimal ext2) {
    }

    public FibLevels calculate(BigDecimal anchorStart, BigDecimal anchorEnd) {
        BigDecimal diff = anchorEnd.subtract(anchorStart);

        // 0% is the anchorEnd (end of the impulse)
        // 100% is the anchorStart (start of the impulse)

        BigDecimal zero = anchorEnd;
        BigDecimal hundred = anchorStart;

        // Golden Zone is 50% to 61.8% retracement back towards the start
        BigDecimal fifty = anchorEnd.subtract(diff.multiply(new BigDecimal("0.50")));
        BigDecimal golden = anchorEnd.subtract(diff.multiply(new BigDecimal("0.618")));

        // Extensions go beyond the 0% line
        BigDecimal ext1 = anchorEnd.add(diff.multiply(new BigDecimal("0.272")));
        BigDecimal ext2 = anchorEnd.add(diff.multiply(new BigDecimal("0.618")));

        return new FibLevels(zero, fifty, golden, hundred, ext1, ext2);
    }
}
