package com.tradebot.repository;

import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LiveTradeExecutionRepository extends JpaRepository<LiveTradeExecution, UUID> {

    List<LiveTradeExecution> findTop50ByOrderByCreatedAtDesc();

    List<LiveTradeExecution> findTop20ByRecommendation_IdOrderByCreatedAtDesc(UUID recommendationId);

    Optional<LiveTradeExecution> findFirstByRecommendation_IdOrderByCreatedAtDesc(UUID recommendationId);

    Optional<LiveTradeExecution> findFirstByRecommendation_IdAndClientRequestId(UUID recommendationId, UUID clientRequestId);

    Optional<LiveTradeExecution> findFirstByRecommendation_IdAndExecutionStateInOrderByCreatedAtDesc(
            UUID recommendationId,
            Collection<LiveTradeExecutionState> states);

    Optional<LiveTradeExecution> findFirstBySymbolAndExecutionStateInOrderByCreatedAtDesc(
            String symbol,
            Collection<LiveTradeExecutionState> states);

    List<LiveTradeExecution> findByExecutionStateInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            Collection<LiveTradeExecutionState> states,
            Instant updatedBefore);
}
