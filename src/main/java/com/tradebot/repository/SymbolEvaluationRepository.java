package com.tradebot.repository;

import com.tradebot.entity.SymbolEvaluation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SymbolEvaluationRepository extends JpaRepository<SymbolEvaluation, UUID> {

    List<SymbolEvaluation> findByScanRunId(UUID scanRunId);

    Page<SymbolEvaluation> findByScanRunId(UUID scanRunId, Pageable pageable);

    Page<SymbolEvaluation> findByScanRunIdAndDecision(UUID scanRunId, String decision, Pageable pageable);

    @Query("SELECT e FROM SymbolEvaluation e WHERE e.scanRunId = :runId AND (:decision IS NULL OR e.decision = :decision) AND (:q IS NULL OR LOWER(e.symbol) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<SymbolEvaluation> search(@Param("runId") UUID runId, @Param("decision") String decision, @Param("q") String q,
            Pageable pageable);

    Optional<SymbolEvaluation> findByScanRunIdAndSymbol(UUID scanRunId, String symbol);

    long countByScanRunIdAndDecision(UUID scanRunId, String decision);

    long countByScanRunId(UUID scanRunId);

    long countByScanRunIdAndRecommendationEligibleTrue(UUID scanRunId);

    long countByScanRunIdAndConflictStateIsNotNull(UUID scanRunId);

    long countByScanRunIdAndSkipReasonCode(UUID scanRunId, String skipReasonCode);
}
