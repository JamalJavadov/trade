package com.tradebot;

import com.tradebot.controller.AiSuggestionController;
import com.tradebot.dto.AiSuggestionLatestResponseDTO;
import com.tradebot.entity.AiSuggestionBatch;
import com.tradebot.entity.AiSuggestionItem;
import com.tradebot.entity.StrategyConfigVersion;
import com.tradebot.repository.AiSuggestionBatchRepository;
import com.tradebot.repository.AiSuggestionItemRepository;
import com.tradebot.repository.StrategyConfigVersionRepository;
import com.tradebot.service.SuggestionBatchService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiSuggestionControllerTest {

    @Test
    void latestResponseUsesStableDtoShape() {
        AiSuggestionBatchRepository batchRepository = mock(AiSuggestionBatchRepository.class);
        AiSuggestionItemRepository itemRepository = mock(AiSuggestionItemRepository.class);
        StrategyConfigVersionRepository strategyConfigVersionRepository = mock(StrategyConfigVersionRepository.class);
        SuggestionBatchService suggestionBatchService = mock(SuggestionBatchService.class);
        AiSuggestionController controller = new AiSuggestionController(
                batchRepository,
                itemRepository,
                strategyConfigVersionRepository,
                suggestionBatchService);

        UUID batchId = UUID.randomUUID();
        AiSuggestionBatch batch = new AiSuggestionBatch();
        batch.setId(batchId);
        batch.setCreatedAt(Instant.parse("2026-03-07T10:00:00Z"));
        batch.setBasedOnLastNTrades(10);
        batch.setStatus("PROPOSED");
        batch.setSummary("Tighten the live management rules.");

        AiSuggestionItem item = new AiSuggestionItem();
        item.setBatchId(batchId);
        item.setKey("management.timeStopMinutes");
        item.setProposedValue("75");
        item.setReason("Reduce long tail losses.");
        item.setImpactHypothesis("Lower average hold time.");
        item.setRiskOfChange("MEDIUM");
        item.setStatus("PROPOSED");

        StrategyConfigVersion activeVersion = new StrategyConfigVersion();
        activeVersion.setVersion(7);

        when(batchRepository.findFirstByOrderByCreatedAtDesc()).thenReturn(Optional.of(batch));
        when(itemRepository.findByBatchId(batchId)).thenReturn(List.of(item));
        when(strategyConfigVersionRepository.findByActiveTrue()).thenReturn(Optional.of(activeVersion));

        AiSuggestionLatestResponseDTO response = controller.getLatest();

        assertThat(response.getCurrentActiveConfigVersion()).isEqualTo(7);
        assertThat(response.getBatch()).isNotNull();
        assertThat(response.getBatch().getItems())
                .singleElement()
                .satisfies(mapped -> {
                    assertThat(mapped.getId()).isEqualTo(batchId + ":management.timeStopMinutes");
                    assertThat(mapped.getRiskOfChange()).isEqualTo("MEDIUM");
                });
    }

    @Test
    void latestResponseReturnsNullBatchWhenNoSuggestionsExist() {
        AiSuggestionBatchRepository batchRepository = mock(AiSuggestionBatchRepository.class);
        AiSuggestionItemRepository itemRepository = mock(AiSuggestionItemRepository.class);
        StrategyConfigVersionRepository strategyConfigVersionRepository = mock(StrategyConfigVersionRepository.class);
        SuggestionBatchService suggestionBatchService = mock(SuggestionBatchService.class);
        AiSuggestionController controller = new AiSuggestionController(
                batchRepository,
                itemRepository,
                strategyConfigVersionRepository,
                suggestionBatchService);

        when(batchRepository.findFirstByOrderByCreatedAtDesc()).thenReturn(Optional.empty());
        when(strategyConfigVersionRepository.findByActiveTrue()).thenReturn(Optional.empty());
        when(strategyConfigVersionRepository.findFirstByOrderByVersionDesc()).thenReturn(Optional.empty());

        AiSuggestionLatestResponseDTO response = controller.getLatest();

        assertThat(response.getCurrentActiveConfigVersion()).isEqualTo(1);
        assertThat(response.getBatch()).isNull();
    }
}
