package com.tradebot.repository;

import com.tradebot.entity.SessionSymbolDecisionAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SessionSymbolDecisionAuditRepository extends JpaRepository<SessionSymbolDecisionAudit, UUID> {

    List<SessionSymbolDecisionAudit> findBySession_IdOrderByEventTsDesc(UUID sessionId);

    List<SessionSymbolDecisionAudit> findBySession_IdOrderByEventTsAsc(UUID sessionId);

    List<SessionSymbolDecisionAudit> findBySession_IdAndSymbolOrderByEventTsDesc(UUID sessionId, String symbol);

    List<SessionSymbolDecisionAudit> findByExecution_IdOrderByEventTsAsc(UUID executionId);

    boolean existsBySession_IdAndRecommendation_IdAndEventTypeIn(
            UUID sessionId,
            UUID recommendationId,
            Collection<String> eventTypes);
}
