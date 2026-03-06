package com.tradebot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.dto.StrategyTuningConfig;
import com.tradebot.entity.AiSuggestionItem;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class LiveSuggestionValidationService {

    private static final Set<String> LOCKED_KEY_SNIPPETS = Set.of(
            "minrr",
            "risk",
            "maxequitypct",
            "maxbudgetpct",
            "executiontf",
            "biastf",
            "entrymethod",
            "fractal");

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal TP3_MIN = new BigDecimal("0.10");

    private final ObjectMapper objectMapper;

    public LiveSuggestionValidationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Set<String> allowedKeys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add("sweep.enterZoneMaxCandles");
        keys.add("sweep.invalidBeyond618Candles");
        keys.add("buffers.slBufferPct");
        keys.add("filters.atrSpikeFilterEnabled");
        keys.add("filters.atrSpikeMultiplier");
        keys.add("targets.tp2Enabled");
        keys.add("targets.tp3Enabled");
        keys.add("targets.tp1Pct");
        keys.add("targets.tp2Pct");
        keys.add("rankingWeights.weightRR");
        keys.add("rankingWeights.weightCleanSweep");
        keys.add("rankingWeights.weightReclaimStrength");
        keys.add("rankingWeights.weightLiquidity");
        return keys;
    }

    public StrategyTuningConfig applyAcceptedItems(StrategyTuningConfig active, List<AiSuggestionItem> items) {
        StrategyTuningConfig next = active == null ? new StrategyTuningConfig() : active;
        for (AiSuggestionItem item : items) {
            if (item == null) {
                continue;
            }
            String key = requiredKey(item.getKey());
            JsonNode valueNode = parseNode(item.getProposedValue(), key);
            validateKey(key);
            applyAndValidate(next, key, valueNode);
        }
        normalizeAndValidate(next);
        return next;
    }

    public void validateKey(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase();
        for (String lockedSnippet : LOCKED_KEY_SNIPPETS) {
            if (normalized.contains(lockedSnippet)) {
                throw new IllegalArgumentException("Locked strategy invariant key is not allowed: " + key);
            }
        }
        if (!allowedKeys().contains(key)) {
            throw new IllegalArgumentException("Unsupported live tuning key: " + key);
        }
    }

    private void applyAndValidate(StrategyTuningConfig config, String key, JsonNode valueNode) {
        switch (key) {
            case "sweep.enterZoneMaxCandles" -> config.getSweep().setEnterZoneMaxCandles(asInt(valueNode, key));
            case "sweep.invalidBeyond618Candles" -> config.getSweep().setInvalidBeyond618Candles(asInt(valueNode, key));
            case "buffers.slBufferPct" -> config.getBuffers().setSlBufferPct(asDecimal(valueNode, key));
            case "filters.atrSpikeFilterEnabled" -> config.getFilters().setAtrSpikeFilterEnabled(asBool(valueNode, key));
            case "filters.atrSpikeMultiplier" -> config.getFilters().setAtrSpikeMultiplier(asDecimal(valueNode, key));
            case "targets.tp2Enabled" -> config.getTargets().setTp2Enabled(asBool(valueNode, key));
            case "targets.tp3Enabled" -> config.getTargets().setTp3Enabled(asBool(valueNode, key));
            case "targets.tp1Pct" -> config.getTargets().setTp1Pct(asDecimal(valueNode, key));
            case "targets.tp2Pct" -> config.getTargets().setTp2Pct(asDecimal(valueNode, key));
            case "rankingWeights.weightRR" -> config.getRankingWeights().setWeightRR(asDecimal(valueNode, key));
            case "rankingWeights.weightCleanSweep" ->
                    config.getRankingWeights().setWeightCleanSweep(asDecimal(valueNode, key));
            case "rankingWeights.weightReclaimStrength" ->
                    config.getRankingWeights().setWeightReclaimStrength(asDecimal(valueNode, key));
            case "rankingWeights.weightLiquidity" -> config.getRankingWeights().setWeightLiquidity(asDecimal(valueNode, key));
            default -> throw new IllegalArgumentException("Unsupported live tuning key: " + key);
        }
        normalizeAndValidate(config);
    }

    private void normalizeAndValidate(StrategyTuningConfig config) {
        range(config.getSweep().getEnterZoneMaxCandles(), 1, 6, "sweep.enterZoneMaxCandles");
        range(config.getSweep().getInvalidBeyond618Candles(), 3, 10, "sweep.invalidBeyond618Candles");
        decimalRange(config.getBuffers().getSlBufferPct(),
                new BigDecimal("0.0001"), new BigDecimal("0.0030"), "buffers.slBufferPct");
        decimalRange(config.getFilters().getAtrSpikeMultiplier(),
                new BigDecimal("1.5"), new BigDecimal("6.0"), "filters.atrSpikeMultiplier");

        decimalRange(config.getTargets().getTp1Pct(),
                new BigDecimal("0.30"), new BigDecimal("0.70"), "targets.tp1Pct");
        decimalRange(config.getTargets().getTp2Pct(),
                new BigDecimal("0.10"), new BigDecimal("0.40"), "targets.tp2Pct");

        decimalRange(config.getRankingWeights().getWeightRR(),
                new BigDecimal("0.30"), new BigDecimal("0.80"), "rankingWeights.weightRR");
        decimalRange(config.getRankingWeights().getWeightCleanSweep(),
                new BigDecimal("0.05"), new BigDecimal("0.40"), "rankingWeights.weightCleanSweep");
        decimalRange(config.getRankingWeights().getWeightReclaimStrength(),
                new BigDecimal("0.05"), new BigDecimal("0.40"), "rankingWeights.weightReclaimStrength");
        decimalRange(config.getRankingWeights().getWeightLiquidity(),
                BigDecimal.ZERO, new BigDecimal("0.30"), "rankingWeights.weightLiquidity");

        config.getRankingWeights().normalize();

        BigDecimal tp3 = ONE.subtract(config.getTargets().getTp1Pct().add(config.getTargets().getTp2Pct()));
        if (tp3.compareTo(TP3_MIN) < 0) {
            throw new IllegalArgumentException("targets.tp3Pct must be >= 0.10");
        }
    }

    private JsonNode parseNode(String raw, String key) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("proposed_value is missing for key " + key);
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
                return objectMapper.getNodeFactory().booleanNode(Boolean.parseBoolean(raw));
            }
            try {
                return objectMapper.getNodeFactory().numberNode(new BigDecimal(raw));
            } catch (Exception ignored) {
                return objectMapper.getNodeFactory().textNode(raw);
            }
        }
    }

    private String requiredKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Suggestion key is required");
        }
        return key;
    }

    private int asInt(JsonNode node, String key) {
        if (node.isIntegralNumber()) {
            return node.intValue();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (Exception ignored) {
            }
        }
        throw new IllegalArgumentException("Expected integer for key " + key);
    }

    private boolean asBool(JsonNode node, String key) {
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual() && ("true".equalsIgnoreCase(node.asText()) || "false".equalsIgnoreCase(node.asText()))) {
            return Boolean.parseBoolean(node.asText());
        }
        throw new IllegalArgumentException("Expected boolean for key " + key);
    }

    private BigDecimal asDecimal(JsonNode node, String key) {
        try {
            if (node.isNumber()) {
                return node.decimalValue();
            }
            if (node.isTextual()) {
                return new BigDecimal(node.asText().trim());
            }
        } catch (Exception ignored) {
        }
        throw new IllegalArgumentException("Expected number for key " + key);
    }

    private void range(Integer value, int min, int max, String key) {
        if (value == null || value < min || value > max) {
            throw new IllegalArgumentException(key + " must be in range [" + min + ".." + max + "]");
        }
    }

    private void decimalRange(BigDecimal value, BigDecimal min, BigDecimal max, String key) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(key + " must be in range [" + min + ".." + max + "]");
        }
    }
}
