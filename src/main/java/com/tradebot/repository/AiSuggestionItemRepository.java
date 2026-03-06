package com.tradebot.repository;

import com.tradebot.entity.AiSuggestionItem;
import com.tradebot.entity.AiSuggestionItemId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AiSuggestionItemRepository extends JpaRepository<AiSuggestionItem, AiSuggestionItemId> {
    List<AiSuggestionItem> findByBatchId(UUID batchId);
}
