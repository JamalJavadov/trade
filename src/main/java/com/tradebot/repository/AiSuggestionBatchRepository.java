package com.tradebot.repository;

import com.tradebot.entity.AiSuggestionBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Optional;

@Repository
public interface AiSuggestionBatchRepository extends JpaRepository<AiSuggestionBatch, UUID> {
    long countByStatus(String status);

    Optional<AiSuggestionBatch> findFirstByOrderByCreatedAtDesc();
}
