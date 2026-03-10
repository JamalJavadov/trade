package com.tradebot.repository;

import com.tradebot.entity.LiveTradeClosure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LiveTradeClosureRepository extends JpaRepository<LiveTradeClosure, UUID> {

    Optional<LiveTradeClosure> findByExecution_Id(UUID executionId);

    List<LiveTradeClosure> findBySession_IdOrderByClosedAtDesc(UUID sessionId);
}
