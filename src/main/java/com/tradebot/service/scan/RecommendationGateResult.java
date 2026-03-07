package com.tradebot.service.scan;

import java.util.List;
import java.util.Map;

public record RecommendationGateResult(
        boolean eligible,
        int finalIntegrityScore,
        boolean placeabilityRealismOk,
        boolean aiBlocked,
        boolean dataIntegrityPassed,
        boolean structuralValidationPassed,
        boolean confirmationPassed,
        boolean deterministicValid,
        List<String> rejectionReasons,
        Map<String, Object> details) {
}
