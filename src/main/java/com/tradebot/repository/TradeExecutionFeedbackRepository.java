package com.tradebot.repository;

import com.tradebot.entity.TradeExecutionFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TradeExecutionFeedbackRepository extends JpaRepository<TradeExecutionFeedback, UUID> {

    @Query(value = "SELECT f.* FROM trade_execution_feedback f ORDER BY f.created_at DESC LIMIT :limit", nativeQuery = true)
    List<TradeExecutionFeedback> findLastN(int limit);

    long count();
}
