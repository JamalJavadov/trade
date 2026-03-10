package com.tradebot.repository;

import com.tradebot.entity.LiveTradeExecution;
import com.tradebot.entity.LiveTradeExecutionState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LiveTradeExecutionRepository extends JpaRepository<LiveTradeExecution, UUID> {

    List<LiveTradeExecution> findTop50ByOrderByCreatedAtDesc();

    List<LiveTradeExecution> findTop20ByRecommendation_IdOrderByCreatedAtDesc(UUID recommendationId);

    Optional<LiveTradeExecution> findFirstByRecommendation_IdOrderByCreatedAtDesc(UUID recommendationId);

    @EntityGraph(attributePaths = { "session", "recommendation", "recommendation.scanRun", "recommendation.orderFields" })
    Optional<LiveTradeExecution> findFirstByRecommendation_IdAndClientRequestId(UUID recommendationId, UUID clientRequestId);

    Optional<LiveTradeExecution> findFirstByRecommendation_IdAndExecutionStatusInOrderByCreatedAtDesc(
            UUID recommendationId,
            Collection<LiveTradeExecutionState> states);

    Optional<LiveTradeExecution> findFirstBySymbolAndExecutionStatusInOrderByCreatedAtDesc(
            String symbol,
            Collection<LiveTradeExecutionState> states);

    List<LiveTradeExecution> findTop20BySession_IdOrderByCreatedAtDesc(UUID sessionId);

    List<LiveTradeExecution> findBySession_IdOrderByCreatedAtDesc(UUID sessionId);

    List<LiveTradeExecution> findBySession_IdAndExecutionStatusInOrderByCreatedAtAsc(
            UUID sessionId,
            Collection<LiveTradeExecutionState> states);

    long countBySession_Id(UUID sessionId);

    long countBySession_IdAndExecutionStatusIn(
            UUID sessionId,
            Collection<LiveTradeExecutionState> states);

    @Query("""
            select coalesce(sum(e.realizedNetPnlUsdt), 0)
            from LiveTradeExecution e
            where e.session.id = :sessionId
            """)
    BigDecimal sumRealizedNetPnlBySessionId(UUID sessionId);

    List<LiveTradeExecution> findByExecutionStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            Collection<LiveTradeExecutionState> states,
            Instant updatedBefore);

    List<LiveTradeExecution> findBySession_IdAndPositionSlotIsNotNullAndExecutionStatusInOrderByPositionSlotAsc(
            UUID sessionId,
            Collection<LiveTradeExecutionState> states);

    @Query("""
            select execution
            from LiveTradeExecution execution
            where execution.session.id = :sessionId
              and execution.executionStatus in :states
            order by
                case when execution.positionSlot is null then 1 else 0 end,
                execution.positionSlot asc,
                execution.createdAt asc
            """)
    List<LiveTradeExecution> findBySession_IdAndExecutionStatusInOrderByCloseAllPriority(
            UUID sessionId,
            Collection<LiveTradeExecutionState> states);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select execution from LiveTradeExecution execution where execution.id = :id")
    Optional<LiveTradeExecution> findByIdForUpdate(UUID id);

    @Override
    @EntityGraph(attributePaths = { "session", "recommendation", "recommendation.scanRun", "recommendation.orderFields" })
    Optional<LiveTradeExecution> findById(UUID id);

    default Optional<LiveTradeExecution> findFirstByRecommendation_IdAndExecutionStateInOrderByCreatedAtDesc(
            UUID recommendationId,
            Collection<LiveTradeExecutionState> states) {
        return findFirstByRecommendation_IdAndExecutionStatusInOrderByCreatedAtDesc(recommendationId, states);
    }

    default Optional<LiveTradeExecution> findFirstBySymbolAndExecutionStateInOrderByCreatedAtDesc(
            String symbol,
            Collection<LiveTradeExecutionState> states) {
        return findFirstBySymbolAndExecutionStatusInOrderByCreatedAtDesc(symbol, states);
    }

    default List<LiveTradeExecution> findTop20ByBudgetTargetSession_IdOrderByCreatedAtDesc(UUID budgetTargetSessionId) {
        return findTop20BySession_IdOrderByCreatedAtDesc(budgetTargetSessionId);
    }

    default List<LiveTradeExecution> findByBudgetTargetSession_IdAndExecutionStateInOrderByCreatedAtAsc(
            UUID budgetTargetSessionId,
            Collection<LiveTradeExecutionState> states) {
        return findBySession_IdAndExecutionStatusInOrderByCreatedAtAsc(budgetTargetSessionId, states);
    }

    default long countByBudgetTargetSession_Id(UUID budgetTargetSessionId) {
        return countBySession_Id(budgetTargetSessionId);
    }

    default long countByBudgetTargetSession_IdAndExecutionStateIn(
            UUID budgetTargetSessionId,
            Collection<LiveTradeExecutionState> states) {
        return countBySession_IdAndExecutionStatusIn(budgetTargetSessionId, states);
    }

    default BigDecimal sumRealizedNetPnlByBudgetTargetSessionId(UUID budgetTargetSessionId) {
        return sumRealizedNetPnlBySessionId(budgetTargetSessionId);
    }

    default List<LiveTradeExecution> findByExecutionStateInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            Collection<LiveTradeExecutionState> states,
            Instant updatedBefore) {
        return findByExecutionStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(states, updatedBefore);
    }
}
