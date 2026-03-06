package com.tradebot.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.controlcenter.ControlCenterSettingsProvider;
import com.tradebot.demo.dto.DemoTuningConfig;
import com.tradebot.demo.entity.DemoStrategyConfigVersion;
import com.tradebot.demo.repository.DemoStrategyConfigVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class DemoStrategyConfigProvider {

    private static final long CACHE_TTL_MS = 60_000L;

    private final DemoStrategyConfigVersionRepository repository;
    private final ControlCenterSettingsProvider controlCenterSettingsProvider;
    private final DemoSuggestionValidationService validationService;
    private final ObjectMapper objectMapper;

    private volatile DemoTuningConfig cachedConfig;
    private volatile DemoStrategyConfigVersion cachedVersion;
    private volatile long cachedAtMillis = 0L;

    @Transactional
    public DemoStrategyConfigVersion ensureActiveVersion() {
        DemoStrategyConfigVersion active = repository.findByActiveTrue().orElse(null);
        if (active == null) {
            DemoTuningConfig baseline = defaultConfig();
            validationService.normalizeAndValidate(baseline);
            DemoStrategyConfigVersion seeded = new DemoStrategyConfigVersion();
            seeded.setVersion(repository.findFirstByOrderByVersionDesc()
                    .map(DemoStrategyConfigVersion::getVersion)
                    .orElse(0) + 1);
            seeded.setCreatedAt(Instant.now());
            seeded.setActive(true);
            seeded.setConfigJson(toJson(baseline));
            seeded.setChangeReason("Initial demo tuning baseline");
            active = repository.save(seeded);
            invalidateCache();
        }
        return migrateIfNeeded(active);
    }

    @Transactional
    public DemoStrategyConfigVersion getActiveConfigVersion() {
        if (isCacheFresh() && cachedVersion != null) {
            return cachedVersion;
        }
        DemoStrategyConfigVersion active = ensureActiveVersion();
        refreshCache(active, parseConfig(active.getConfigJson()));
        return active;
    }

    @Transactional
    public DemoTuningConfig getActiveConfig() {
        if (isCacheFresh() && cachedConfig != null) {
            return cachedConfig;
        }
        DemoStrategyConfigVersion active = ensureActiveVersion();
        DemoTuningConfig parsed = parseConfig(active.getConfigJson());
        refreshCache(active, parsed);
        return parsed;
    }

    public synchronized void invalidateCache() {
        cachedConfig = null;
        cachedVersion = null;
        cachedAtMillis = 0L;
    }

    public DemoTuningConfig defaultConfig() {
        DemoTuningConfig config = new DemoTuningConfig();
        config.getManagement().setTimeStopMinutes(
                Math.max(controlCenterSettingsProvider.getConfigSnapshot().getDemoTrading().getTimeStopMinutes(), 1));
        return config;
    }

    private DemoStrategyConfigVersion migrateIfNeeded(DemoStrategyConfigVersion active) {
        if (!isOldSchema(active.getConfigJson())) {
            return active;
        }

        log.info("Auto-migrating demo strategy config version {} to DT3 DemoTuningConfig schema", active.getVersion());
        DemoTuningConfig migrated = mapOldConfig(active.getConfigJson());
        validationService.normalizeAndValidate(migrated);

        active.setActive(false);
        repository.save(active);

        DemoStrategyConfigVersion next = new DemoStrategyConfigVersion();
        next.setVersion(repository.findFirstByOrderByVersionDesc()
                .map(DemoStrategyConfigVersion::getVersion)
                .orElse(0) + 1);
        next.setCreatedAt(Instant.now());
        next.setActive(true);
        next.setConfigJson(toJson(migrated));
        next.setChangeReason("DT3 auto-migration from version " + active.getVersion());
        DemoStrategyConfigVersion saved = repository.save(next);
        invalidateCache();
        return saved;
    }

    private boolean isOldSchema(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            return root.has("tuning") || root.has("executionTf") || root.has("minRr") || root.has("risk");
        } catch (Exception e) {
            return true;
        }
    }

    private DemoTuningConfig mapOldConfig(String rawJson) {
        DemoTuningConfig config = defaultConfig();
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode tuning = root.path("tuning");
            if (tuning.isMissingNode()) {
                tuning = root;
            }

            config.getSweep().setMaxSweepCandles(intOrDefault(
                    tuning.path("sweep").path("enterZoneMaxCandles"),
                    config.getSweep().getMaxSweepCandles()));
            config.getSweep().setInvalidBeyond618Candles(intOrDefault(
                    tuning.path("sweep").path("invalidBeyond618Candles"),
                    config.getSweep().getInvalidBeyond618Candles()));

            // Old config used slBufferPct; DT3 uses slBufferTicks.
            config.getBuffers().setSlBufferTicks(1);

            config.getFilters().setAtrSpikeFilterEnabled(boolOrDefault(
                    tuning.path("filters").path("atrSpikeFilterEnabled"),
                    config.getFilters().isAtrSpikeFilterEnabled()));
            config.getFilters().setAtrSpikeMultiplier(decimalOrDefault(
                    tuning.path("filters").path("atrSpikeMultiplier"),
                    config.getFilters().getAtrSpikeMultiplier()));
            config.getFilters().setMinReclaimStrength(BigDecimal.ZERO);

            config.getRankingWeights().setWeightRR(decimalOrDefault(
                    tuning.path("rankingWeights").path("weightRR"),
                    config.getRankingWeights().getWeightRR()));
            config.getRankingWeights().setWeightCleanSweep(decimalOrDefault(
                    tuning.path("rankingWeights").path("weightCleanSweep"),
                    config.getRankingWeights().getWeightCleanSweep()));
            config.getRankingWeights().setWeightReclaimStrength(decimalOrDefault(
                    tuning.path("rankingWeights").path("weightReclaimStrength"),
                    config.getRankingWeights().getWeightReclaimStrength()));
            config.getRankingWeights().setWeightLiquidity(decimalOrDefault(
                    tuning.path("rankingWeights").path("weightLiquidity"),
                    config.getRankingWeights().getWeightLiquidity()));

            config.getManagement().setPartialTp1Pct(decimalOrDefault(
                    tuning.path("targets").path("tp1Pct"),
                    config.getManagement().getPartialTp1Pct()));
            config.getManagement().setPartialTp2Pct(decimalOrDefault(
                    tuning.path("targets").path("tp2Pct"),
                    config.getManagement().getPartialTp2Pct()));
            config.getManagement().setTimeStopMinutes(
                    Math.max(controlCenterSettingsProvider.getConfigSnapshot().getDemoTrading().getTimeStopMinutes(), 1));
        } catch (Exception e) {
            log.warn("Unable to map old demo strategy config, using defaults: {}", e.getMessage());
        }
        return config;
    }

    private void refreshCache(DemoStrategyConfigVersion version, DemoTuningConfig config) {
        cachedVersion = version;
        cachedConfig = config;
        cachedAtMillis = System.currentTimeMillis();
    }

    private boolean isCacheFresh() {
        return cachedAtMillis > 0L && (System.currentTimeMillis() - cachedAtMillis) < CACHE_TTL_MS;
    }

    private DemoTuningConfig parseConfig(String configJson) {
        try {
            DemoTuningConfig config = objectMapper.readValue(configJson, DemoTuningConfig.class);
            validationService.normalizeAndValidate(config);
            return config;
        } catch (Exception e) {
            log.warn("Failed to parse demo tuning config; falling back to defaults: {}", e.getMessage());
            DemoTuningConfig fallback = defaultConfig();
            validationService.normalizeAndValidate(fallback);
            return fallback;
        }
    }

    private String toJson(DemoTuningConfig config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize demo tuning config", e);
        }
    }

    private int intOrDefault(JsonNode node, int fallback) {
        return node != null && node.isInt() ? node.intValue() : fallback;
    }

    private boolean boolOrDefault(JsonNode node, boolean fallback) {
        return node != null && node.isBoolean() ? node.booleanValue() : fallback;
    }

    private java.math.BigDecimal decimalOrDefault(JsonNode node, java.math.BigDecimal fallback) {
        try {
            if (node != null && node.isNumber()) {
                return node.decimalValue();
            }
            if (node != null && node.isTextual()) {
                return new java.math.BigDecimal(node.asText());
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}
