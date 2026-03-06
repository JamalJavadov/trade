package com.tradebot.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.demo.dto.DemoTuningConfig;
import com.tradebot.demo.entity.DemoAiSuggestionItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DemoSuggestionValidationService {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal TP3_MIN = new BigDecimal("0.10");

    private final ObjectMapper objectMapper;

    public DemoTuningConfig applyAcceptedItems(DemoTuningConfig active, List<DemoAiSuggestionItem> items) {
        DemoTuningConfig next = deepCopy(active);
        for (DemoAiSuggestionItem item : items) {
            JsonNode valueNode = parseNode(item.getProposedValue());
            applyAndValidate(next, item.getKey(), valueNode);
        }
        normalizeAndValidate(next);
        return next;
    }

    public void validateAiItem(JsonNode itemNode) {
        if (itemNode == null || itemNode.isMissingNode()) {
            throw new IllegalArgumentException("AI item is missing");
        }
        String key = requiredText(itemNode, "key");
        JsonNode proposedValue = itemNode.path("proposed_value");
        if (proposedValue.isMissingNode() || proposedValue.isNull()) {
            throw new IllegalArgumentException("AI item proposed_value missing for key " + key);
        }
        applyAndValidate(new DemoTuningConfig(), key, proposedValue);
    }

    public Set<String> allowedKeys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add("sweep.maxSweepCandles");
        keys.add("sweep.invalidBeyond618Candles");
        keys.add("buffers.slBufferTicks");
        keys.add("filters.atrSpikeFilterEnabled");
        keys.add("filters.atrSpikeMultiplier");
        keys.add("filters.minReclaimStrength");
        keys.add("rankingWeights.weightRR");
        keys.add("rankingWeights.weightCleanSweep");
        keys.add("rankingWeights.weightReclaimStrength");
        keys.add("rankingWeights.weightLiquidity");
        keys.add("management.partialTp1Pct");
        keys.add("management.partialTp2Pct");
        keys.add("management.timeStopMinutes");
        return keys;
    }

    public void normalizeAndValidate(DemoTuningConfig config) {
        if (config.getSweep() == null) {
            config.setSweep(new DemoTuningConfig.Sweep());
        }
        if (config.getBuffers() == null) {
            config.setBuffers(new DemoTuningConfig.Buffers());
        }
        if (config.getFilters() == null) {
            config.setFilters(new DemoTuningConfig.Filters());
        }
        if (config.getRankingWeights() == null) {
            config.setRankingWeights(new DemoTuningConfig.RankingWeights());
        }
        if (config.getManagement() == null) {
            config.setManagement(new DemoTuningConfig.Management());
        }

        range(config.getSweep().getMaxSweepCandles(), 1, 6, "sweep.maxSweepCandles");
        range(config.getSweep().getInvalidBeyond618Candles(), 3, 10, "sweep.invalidBeyond618Candles");
        range(config.getBuffers().getSlBufferTicks(), 0, 5, "buffers.slBufferTicks");

        decimalRange(config.getFilters().getAtrSpikeMultiplier(),
                new BigDecimal("1.5"), new BigDecimal("6.0"), "filters.atrSpikeMultiplier");
        decimalRange(config.getFilters().getMinReclaimStrength(),
                BigDecimal.ZERO, ONE, "filters.minReclaimStrength");

        decimalRange(config.getRankingWeights().getWeightRR(),
                new BigDecimal("0.30"), new BigDecimal("0.80"), "rankingWeights.weightRR");
        decimalRange(config.getRankingWeights().getWeightCleanSweep(),
                new BigDecimal("0.05"), new BigDecimal("0.40"), "rankingWeights.weightCleanSweep");
        decimalRange(config.getRankingWeights().getWeightReclaimStrength(),
                new BigDecimal("0.05"), new BigDecimal("0.40"), "rankingWeights.weightReclaimStrength");
        decimalRange(config.getRankingWeights().getWeightLiquidity(),
                BigDecimal.ZERO, new BigDecimal("0.30"), "rankingWeights.weightLiquidity");

        decimalRange(config.getManagement().getPartialTp1Pct(),
                new BigDecimal("0.30"), new BigDecimal("0.70"), "management.partialTp1Pct");
        decimalRange(config.getManagement().getPartialTp2Pct(),
                new BigDecimal("0.10"), new BigDecimal("0.40"), "management.partialTp2Pct");
        range(config.getManagement().getTimeStopMinutes(), 30, 180, "management.timeStopMinutes");

        config.normalize();

        BigDecimal tp3 = config.getPartialTp3Pct();
        if (tp3.compareTo(TP3_MIN) < 0) {
            throw new IllegalArgumentException("management.partialTp3Pct must be >= 0.10");
        }
    }

    public void applyAndValidate(DemoTuningConfig config, String key, JsonNode valueNode) {
        switch (key) {
            case "sweep.maxSweepCandles" -> config.getSweep().setMaxSweepCandles(asInt(valueNode, key));
            case "sweep.invalidBeyond618Candles" -> config.getSweep().setInvalidBeyond618Candles(asInt(valueNode, key));
            case "buffers.slBufferTicks" -> config.getBuffers().setSlBufferTicks(asInt(valueNode, key));
            case "filters.atrSpikeFilterEnabled" -> config.getFilters().setAtrSpikeFilterEnabled(asBool(valueNode, key));
            case "filters.atrSpikeMultiplier" -> config.getFilters().setAtrSpikeMultiplier(asDecimal(valueNode, key));
            case "filters.minReclaimStrength" -> config.getFilters().setMinReclaimStrength(asDecimal(valueNode, key));
            case "rankingWeights.weightRR" -> config.getRankingWeights().setWeightRR(asDecimal(valueNode, key));
            case "rankingWeights.weightCleanSweep" ->
                    config.getRankingWeights().setWeightCleanSweep(asDecimal(valueNode, key));
            case "rankingWeights.weightReclaimStrength" ->
                    config.getRankingWeights().setWeightReclaimStrength(asDecimal(valueNode, key));
            case "rankingWeights.weightLiquidity" -> config.getRankingWeights().setWeightLiquidity(asDecimal(valueNode, key));
            case "management.partialTp1Pct" -> config.getManagement().setPartialTp1Pct(asDecimal(valueNode, key));
            case "management.partialTp2Pct" -> config.getManagement().setPartialTp2Pct(asDecimal(valueNode, key));
            case "management.timeStopMinutes" -> config.getManagement().setTimeStopMinutes(asInt(valueNode, key));
            default -> throw new IllegalArgumentException("Unsupported demo tuning key: " + key);
        }
        normalizeAndValidate(config);
    }

    private DemoTuningConfig deepCopy(DemoTuningConfig source) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(source), DemoTuningConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to clone DemoTuningConfig", e);
        }
    }

    private JsonNode parseNode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("proposed_value is missing");
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
                throw new IllegalArgumentException("Invalid proposed_value JSON: " + raw);
            }
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Field '" + field + "' is required");
        }
        return value;
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
