package com.tradebot.demo.repository;

import com.tradebot.demo.entity.DemoAiSuggestionItem;
import com.tradebot.demo.entity.DemoAiSuggestionItemId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DemoAiSuggestionItemRepository extends JpaRepository<DemoAiSuggestionItem, DemoAiSuggestionItemId> {
    List<DemoAiSuggestionItem> findByBatchId(UUID batchId);
}
