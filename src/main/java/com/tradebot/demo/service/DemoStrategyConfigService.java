package com.tradebot.demo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.demo.dto.DemoTuningConfig;
import com.tradebot.demo.entity.DemoAiSuggestionItem;
import com.tradebot.demo.entity.DemoStrategyConfigVersion;
import com.tradebot.demo.repository.DemoStrategyConfigVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DemoStrategyConfigService {

    private final DemoStrategyConfigVersionRepository repository;
    private final DemoStrategyConfigProvider provider;
    private final DemoSuggestionValidationService validationService;
    private final ObjectMapper objectMapper;

    @Transactional
    public DemoStrategyConfigVersion ensureActiveVersion() {
        return provider.ensureActiveVersion();
    }

    @Transactional(readOnly = true)
    public DemoTuningConfig getActiveConfig() {
        return provider.getActiveConfig();
    }

    @Transactional
    public DemoStrategyConfigVersion createNewVersionFromAcceptedItems(UUID batchId, List<DemoAiSuggestionItem> items) {
        return createNewVersionFromAcceptedItems(batchId, null, items);
    }

    @Transactional
    public DemoStrategyConfigVersion createNewVersionFromAcceptedItems(
            UUID batchId,
            String batchSummary,
            List<DemoAiSuggestionItem> items) {

        DemoStrategyConfigVersion activeVersion = provider.getActiveConfigVersion();
        DemoTuningConfig activeConfig = provider.getActiveConfig();
        DemoTuningConfig nextConfig = validationService.applyAcceptedItems(activeConfig, items);

        repository.findByActiveTrue().ifPresent(active -> {
            active.setActive(false);
            repository.save(active);
        });

        int nextVersion = repository.findFirstByOrderByVersionDesc()
                .map(DemoStrategyConfigVersion::getVersion)
                .orElse(0) + 1;

        DemoStrategyConfigVersion created = new DemoStrategyConfigVersion();
        created.setVersion(nextVersion);
        created.setCreatedAt(Instant.now());
        created.setActive(true);
        created.setConfigJson(toJson(nextConfig));

        String summarySuffix = (batchSummary == null || batchSummary.isBlank()) ? "" : " | " + batchSummary;
        created.setChangeReason("Accepted demo AI batch " + batchId + summarySuffix);
        DemoStrategyConfigVersion saved = repository.save(created);
        provider.invalidateCache();
        return saved;
    }

    private String toJson(DemoTuningConfig config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize demo tuning config", e);
        }
    }
}
