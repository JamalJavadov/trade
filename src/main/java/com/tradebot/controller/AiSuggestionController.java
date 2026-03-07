package com.tradebot.controller;

import com.tradebot.dto.AiSuggestionBatchDTO;
import com.tradebot.dto.AiSuggestionLatestResponseDTO;
import com.tradebot.entity.AiSuggestionBatch;
import com.tradebot.entity.AiSuggestionItem;
import com.tradebot.entity.StrategyConfigVersion;
import com.tradebot.operator.RequiresPermission;
import com.tradebot.repository.AiSuggestionBatchRepository;
import com.tradebot.repository.AiSuggestionItemRepository;
import com.tradebot.repository.StrategyConfigVersionRepository;
import com.tradebot.service.SuggestionBatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai/suggestions")
@RequiredArgsConstructor
public class AiSuggestionController {

    private final AiSuggestionBatchRepository batchRepository;
    private final AiSuggestionItemRepository itemRepository;
    private final StrategyConfigVersionRepository strategyConfigVersionRepository;
    private final SuggestionBatchService suggestionBatchService;

    @GetMapping("/latest")
    @RequiresPermission("ai.suggestions.view")
    public AiSuggestionLatestResponseDTO getLatest() {
        AiSuggestionBatch batch = batchRepository.findFirstByOrderByCreatedAtDesc().orElse(null);
        AiSuggestionLatestResponseDTO response = new AiSuggestionLatestResponseDTO();
        response.setCurrentActiveConfigVersion(resolveCurrentActiveConfigVersion());
        response.setBatch(toBatchDto(batch));
        return response;
    }

    @PostMapping("/{id}/accept")
    @RequiresPermission("ai.suggestions.accept_reject")
    public void acceptBatch(@PathVariable UUID id) throws Exception {
        suggestionBatchService.acceptBatch(id);
    }

    @PostMapping("/{id}/reject")
    @RequiresPermission("ai.suggestions.accept_reject")
    public void rejectBatch(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        AiSuggestionBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("AI suggestion batch not found: " + id));
        batch.setStatus("REJECTED");
        if (body != null && body.containsKey("reason")) {
            batch.setRejectReason(body.get("reason"));
        }
        batchRepository.save(batch);

        List<AiSuggestionItem> items = itemRepository.findByBatchId(id);
        items.forEach(i -> {
            i.setStatus("REJECTED");
            itemRepository.save(i);
        });
    }

    private Integer resolveCurrentActiveConfigVersion() {
        return strategyConfigVersionRepository.findByActiveTrue()
                .map(StrategyConfigVersion::getVersion)
                .or(() -> strategyConfigVersionRepository.findFirstByOrderByVersionDesc()
                        .map(StrategyConfigVersion::getVersion))
                .orElse(1);
    }

    private AiSuggestionBatchDTO toBatchDto(AiSuggestionBatch batch) {
        if (batch == null) {
            return null;
        }

        List<AiSuggestionItem> items = itemRepository.findByBatchId(batch.getId());

        AiSuggestionBatchDTO dto = new AiSuggestionBatchDTO();
        dto.setId(batch.getId());
        dto.setCreatedAt(batch.getCreatedAt());
        dto.setBasedOnLastNTrades(batch.getBasedOnLastNTrades());
        dto.setSummary(batch.getSummary());
        dto.setStatus(batch.getStatus());
        dto.setItems(items.stream().map(this::toItemDto).toList());
        return dto;
    }

    private AiSuggestionBatchDTO.SuggestionItemDTO toItemDto(AiSuggestionItem item) {
        AiSuggestionBatchDTO.SuggestionItemDTO dto = new AiSuggestionBatchDTO.SuggestionItemDTO();
        dto.setId(item.getBatchId() + ":" + item.getKey());
        dto.setKey(item.getKey());
        dto.setProposedValue(item.getProposedValue());
        dto.setReason(item.getReason());
        dto.setImpactHypothesis(item.getImpactHypothesis());
        dto.setRiskOfChange(item.getRiskOfChange());
        dto.setStatus(item.getStatus());
        return dto;
    }
}
