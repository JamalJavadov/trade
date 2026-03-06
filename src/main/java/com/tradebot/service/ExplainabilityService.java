package com.tradebot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.dto.ExplanationDTO;
import com.tradebot.entity.SymbolEvaluation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExplainabilityService {

    private final ObjectMapper objectMapper;

    public ExplanationDTO explain(SymbolEvaluation evaluation) {
        ExplanationDTO dto = new ExplanationDTO();
        List<String> bullets = new ArrayList<>();
        List<String> tags = new ArrayList<>();

        Map<String, Object> metrics = parseMetrics(evaluation.getMetricsJson());

        if ("VALID".equals(evaluation.getDecision())) {
            dto.setHeadline("Valid setup aligned with bias");
            tags.add("VALID");

            if (metrics.containsKey("bias")) {
                bullets.add(String.format("Direction clearly identified as %s.", metrics.get("bias")));
            }
            if (metrics.containsKey("rr_tp1")) {
                bullets.add(String.format("Risk/Reward to TP1 is %.2f, meeting minimum thresholds.",
                        getDouble(metrics, "rr_tp1", 0.0)));
            }
            if (metrics.containsKey("confidence_score")) {
                bullets.add(String.format("High structural confidence score of %.2f out of 5.",
                        getDouble(metrics, "confidence_score", 0.0)));
            }
        } else {
            String reason = evaluation.getSkipReasonCode() != null ? evaluation.getSkipReasonCode() : "UNKNOWN";
            tags.add(reason);
            tags.add("NO_TRADE");

            switch (reason) {
                case "RR_TOO_LOW":
                    dto.setHeadline("No trade — Risk/Reward below minimum requirement");
                    double rrTp1 = getDouble(metrics, "rr_tp1", 0.0);
                    double minRr = getDouble(metrics, "min_rr", 2.0);
                    bullets.add(String.format(
                            "Calculated RR to TP1 was %.2f, which is below the minimum required %.2f.", rrTp1, minRr));
                    bullets.add("Stop distance was likely large relative to target, reducing statistical expectancy.");
                    break;
                case "NO_BIAS":
                    dto.setHeadline("No trade — Market structure lacks clear direction");
                    bullets.add(
                            "Algorithm requires a defined Higher-High/Higher-Low or Lower-High/Lower-Low sequence.");
                    bullets.add("Current structure is ranging or conflicting on HTF.");
                    break;
                case "NO_SWEEP_RECLAIM":
                    dto.setHeadline("No trade — Liquidity sweep pending or missing");
                    bullets.add(
                            "The FSD rapid-fill strategy requires a liquidity sweep followed by a confident structural reclaim.");
                    bullets.add("Price action has not demonstrated a clear trap/reversal pattern.");
                    break;
                case "INADEQUATE_FIB_RETRACEMENT":
                    dto.setHeadline("No trade — Pullback not deep enough");
                    bullets.add("Entry requires price to retrace to at least the "
                            + metrics.getOrDefault("min_fib", "0.5") + " Fibonacci level of the impulse leg.");
                    bullets.add("Taking entries too shallow degrades RR.");
                    break;
                default:
                    dto.setHeadline("No trade — Does not meet strict FSD criteria");
                    if (evaluation.getSkipReasonText() != null) {
                        bullets.add(evaluation.getSkipReasonText());
                    } else {
                        bullets.add("Failed validation checks during pipeline processing.");
                    }
            }
        }

        dto.setBullets(bullets);
        dto.setTags(tags);
        return dto;
    }

    private Map<String, Object> parseMetrics(String json) {
        if (json == null || json.isBlank())
            return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to parse metrics JSON for explainability: {}", e.getMessage());
            return Map.of();
        }
    }

    private double getDouble(Map<String, Object> map, String key, double defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        return defaultVal;
    }
}
