package com.tradebot.repository;

import com.tradebot.entity.LiveTradeExecutionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LiveTradeExecutionEventRepository extends JpaRepository<LiveTradeExecutionEvent, UUID> {

    List<LiveTradeExecutionEvent> findByExecution_IdOrderByEventTsAsc(UUID executionId);

    List<LiveTradeExecutionEvent> findByExecution_Session_IdOrderByEventTsAsc(UUID sessionId);

    List<LiveTradeExecutionEvent> findByExecution_Session_IdOrderByEventTsDesc(UUID sessionId);

    default List<LiveTradeExecutionEvent> findByExecution_IdOrderByCreatedAtAsc(UUID executionId) {
        return findByExecution_IdOrderByEventTsAsc(executionId);
    }
}
