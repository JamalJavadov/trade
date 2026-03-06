package com.tradebot.controller;

import com.tradebot.entity.AiSuggestionBatch;
import com.tradebot.entity.AiSuggestionItem;
import com.tradebot.operator.RequiresPermission;
import com.tradebot.repository.AiSuggestionBatchRepository;
import com.tradebot.repository.AiSuggestionItemRepository;
import com.tradebot.service.SuggestionBatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/v1/ai/suggestions")
@RequiredArgsConstructor
public class AiSuggestionController {

    private final AiSuggestionBatchRepository batchRepository;
    private final AiSuggestionItemRepository itemRepository;
    private final SuggestionBatchService suggestionBatchService;

    @GetMapping("/latest")
    @RequiresPermission("ai.suggestions.view")
    public Map<String, Object> getLatest() {
        AiSuggestionBatch batch = batchRepository.findFirstByOrderByCreatedAtDesc().orElse(null);
        if (batch == null)
            return Map.of("message", "No suggestions found");

        List<AiSuggestionItem> items = itemRepository.findByBatchId(batch.getId());

        Map<String, Object> response = new HashMap<>();
        response.put("batch", batch);
        response.put("items", items);
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
        AiSuggestionBatch batch = batchRepository.findById(id).orElseThrow();
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
}
