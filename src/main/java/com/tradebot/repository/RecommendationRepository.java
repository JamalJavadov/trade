package com.tradebot.repository;

import com.tradebot.entity.Recommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.Optional;

public interface RecommendationRepository extends JpaRepository<Recommendation, UUID> {
    Optional<Recommendation> findFirstByOrderByCreatedAtDesc();

    Optional<Recommendation> findFirstByScanRunIdOrderByCreatedAtDesc(UUID scanRunId);
}
