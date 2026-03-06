package com.tradebot.demo.repository;

import com.tradebot.demo.entity.DemoAiSuggestionBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DemoAiSuggestionBatchRepository extends JpaRepository<DemoAiSuggestionBatch, UUID> {
    Optional<DemoAiSuggestionBatch> findFirstByStatusOrderByCreatedAtDesc(String status);

    long countByStatus(String status);

    Optional<DemoAiSuggestionBatch> findFirstByOrderByCreatedAtDesc();
}
