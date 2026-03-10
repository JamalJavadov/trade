package com.tradebot.repository;

import com.tradebot.entity.LiveTradeOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LiveTradeOrderRepository extends JpaRepository<LiveTradeOrder, UUID> {

    Optional<LiveTradeOrder> findByExecution_IdAndOrderRole(UUID executionId, String orderRole);

    List<LiveTradeOrder> findByExecution_IdOrderByCreatedAtAsc(UUID executionId);

    List<LiveTradeOrder> findBySession_IdOrderByCreatedAtDesc(UUID sessionId);
}
