package com.tradebot.service.scan;

import com.tradebot.service.FibonacciCalculator;
import com.tradebot.service.ImpulseLegDetector;
import com.tradebot.service.RiskAndSizingCalculator;
import com.tradebot.service.SweepReclaimDetector;

import java.util.Map;

public record DeterministicStrategyResult(
        String decision,
        String side,
        String bias,
        String skipReasonCode,
        String skipReasonText,
        boolean valid,
        ImpulseLegDetector.ImpulseLeg impulse,
        FibonacciCalculator.FibLevels fibLevels,
        SweepReclaimDetector.Setup setup,
        RiskAndSizingCalculator.ExecutionPlan executionPlan,
        Map<String, Object> metrics,
        Map<String, Object> diagnostics,
        Map<String, Object> intermediateSignals) {

    public DeterministicEvidence toEvidence() {
        return new DeterministicEvidence(
                decision,
                side,
                bias,
                skipReasonCode,
                skipReasonText,
                valid,
                metrics,
                diagnostics,
                intermediateSignals);
    }
}
