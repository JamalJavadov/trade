package com.tradebot.repository;

import com.tradebot.entity.Recommendation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface RecommendationRepository extends JpaRepository<Recommendation, UUID> {
    @EntityGraph(attributePaths = { "scanRun", "orderFields" })
    Optional<Recommendation> findFirstByOrderByCreatedAtDesc();

    Optional<Recommendation> findFirstByScanRunIdOrderByCreatedAtDesc(UUID scanRunId);

    Page<Recommendation> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = { "scanRun", "orderFields" })
    @Query("select recommendation from Recommendation recommendation where recommendation.id = :id")
    Optional<Recommendation> findDetailedById(UUID id);
}
