package com.tradebot.repository;

import com.tradebot.entity.LiveTradeExecutionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LiveTradeExecutionEventRepository extends JpaRepository<LiveTradeExecutionEvent, UUID> {

    List<LiveTradeExecutionEvent> findByExecution_IdOrderByCreatedAtAsc(UUID executionId);
}
