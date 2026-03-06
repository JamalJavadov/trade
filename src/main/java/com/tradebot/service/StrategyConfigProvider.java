package com.tradebot.service;

import com.tradebot.dto.StrategyTuningConfig;
import com.tradebot.entity.StrategyConfigVersion;
import com.tradebot.repository.StrategyConfigVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyConfigProvider {

    private final StrategyConfigVersionRepository repository;
    private final ObjectMapper objectMapper;

    // Simple 60s cache
    private StrategyTuningConfig cachedConfig;
    private long lastFetchTime = 0;
    private static final long CACHE_TTL_MS = 60000;

    public StrategyTuningConfig getActiveConfig() {
        if (cachedConfig != null && System.currentTimeMillis() - lastFetchTime < CACHE_TTL_MS) {
            return cachedConfig;
        }

        try {
            Optional<StrategyConfigVersion> activeOpt = repository.findByActiveTrue();
            if (activeOpt.isPresent()) {
                StrategyTuningConfig config = objectMapper.readValue(
                        activeOpt.get().getConfigJson(),
                        StrategyTuningConfig.class);
                // Ensure ranking weights are valid
                config.getRankingWeights().normalize();

                cachedConfig = config;
                lastFetchTime = System.currentTimeMillis();
                log.debug("Loaded active StrategyTuningConfig version {}", activeOpt.get().getVersion());
                return cachedConfig;
            }
        } catch (Exception e) {
            log.error("Failed to parse active strategy config from DB. Falling back to defaults.", e);
        }

        // Fallback
        cachedConfig = new StrategyTuningConfig();
        cachedConfig.getRankingWeights().normalize();
        lastFetchTime = System.currentTimeMillis();
        log.info("Loaded default StrategyTuningConfig.");
        return cachedConfig;
    }

    public void invalidateCache() {
        this.cachedConfig = null;
        this.lastFetchTime = 0;
    }
}
