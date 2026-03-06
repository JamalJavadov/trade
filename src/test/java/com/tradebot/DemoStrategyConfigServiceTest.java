package com.tradebot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebot.demo.dto.DemoTuningConfig;
import com.tradebot.demo.entity.DemoAiSuggestionItem;
import com.tradebot.demo.entity.DemoStrategyConfigVersion;
import com.tradebot.demo.repository.DemoStrategyConfigVersionRepository;
import com.tradebot.demo.service.DemoStrategyConfigProvider;
import com.tradebot.demo.service.DemoStrategyConfigService;
import com.tradebot.demo.service.DemoSuggestionValidationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemoStrategyConfigServiceTest {

    @Test
    void acceptingSuggestionsCreatesNewVersion() {
        DemoStrategyConfigVersionRepository repository = mock(DemoStrategyConfigVersionRepository.class);
        DemoStrategyConfigProvider provider = mock(DemoStrategyConfigProvider.class);
        ObjectMapper objectMapper = new ObjectMapper();
        DemoSuggestionValidationService validation = new DemoSuggestionValidationService(objectMapper);
        DemoStrategyConfigService service = new DemoStrategyConfigService(repository, provider, validation, objectMapper);

        DemoTuningConfig baseline = new DemoTuningConfig();
        DemoStrategyConfigVersion active = new DemoStrategyConfigVersion();
        active.setId(UUID.randomUUID());
        active.setVersion(1);
        active.setCreatedAt(Instant.now());
        active.setActive(true);

        when(provider.getActiveConfigVersion()).thenReturn(active);
        when(provider.getActiveConfig()).thenReturn(baseline);
        when(repository.findByActiveTrue()).thenReturn(Optional.of(active));
        when(repository.findFirstByOrderByVersionDesc()).thenReturn(Optional.of(active));
        when(repository.save(any(DemoStrategyConfigVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DemoAiSuggestionItem item = new DemoAiSuggestionItem();
        item.setKey("sweep.maxSweepCandles");
        item.setProposedValue("4");

        DemoStrategyConfigVersion saved = service.createNewVersionFromAcceptedItems(UUID.randomUUID(), List.of(item));

        assertEquals(2, saved.getVersion());
        assertEquals(true, saved.getActive());
    }

    @Test
    void outOfRangeSuggestionIsRejected() {
        DemoStrategyConfigVersionRepository repository = mock(DemoStrategyConfigVersionRepository.class);
        DemoStrategyConfigProvider provider = mock(DemoStrategyConfigProvider.class);
        ObjectMapper objectMapper = new ObjectMapper();
        DemoSuggestionValidationService validation = new DemoSuggestionValidationService(objectMapper);
        DemoStrategyConfigService service = new DemoStrategyConfigService(repository, provider, validation, objectMapper);

        DemoTuningConfig baseline = new DemoTuningConfig();
        DemoStrategyConfigVersion active = new DemoStrategyConfigVersion();
        active.setId(UUID.randomUUID());
        active.setVersion(1);
        active.setCreatedAt(Instant.now());
        active.setActive(true);

        when(provider.getActiveConfigVersion()).thenReturn(active);
        when(provider.getActiveConfig()).thenReturn(baseline);
        when(repository.findByActiveTrue()).thenReturn(Optional.of(active));
        when(repository.findFirstByOrderByVersionDesc()).thenReturn(Optional.of(active));

        DemoAiSuggestionItem item = new DemoAiSuggestionItem();
        item.setKey("management.timeStopMinutes");
        item.setProposedValue("999");

        assertThrows(IllegalArgumentException.class,
                () -> service.createNewVersionFromAcceptedItems(UUID.randomUUID(), List.of(item)));
    }
}
