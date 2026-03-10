package com.tradebot.repository;

import com.tradebot.entity.BudgetTargetSessionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BudgetTargetSessionEventRepository extends JpaRepository<BudgetTargetSessionEvent, UUID> {

    List<BudgetTargetSessionEvent> findTop50BySession_IdOrderByEventTsDesc(UUID sessionId);

    List<BudgetTargetSessionEvent> findBySession_IdOrderByEventTsAsc(UUID sessionId);

    List<BudgetTargetSessionEvent> findBySession_IdOrderByEventTsDesc(UUID sessionId);
}
