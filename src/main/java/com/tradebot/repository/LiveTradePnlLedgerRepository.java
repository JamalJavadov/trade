package com.tradebot.repository;

import com.tradebot.entity.LiveTradePnlLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LiveTradePnlLedgerRepository extends JpaRepository<LiveTradePnlLedger, UUID> {

    Optional<LiveTradePnlLedger> findBySourceTypeAndSourceRef(String sourceType, String sourceRef);

    List<LiveTradePnlLedger> findBySession_IdOrderByEventTsDesc(UUID sessionId);

    List<LiveTradePnlLedger> findByExecution_IdOrderByEventTsDesc(UUID executionId);

    @Query("""
            select coalesce(sum(entry.amountUsdt), 0)
            from LiveTradePnlLedger entry
            where entry.session.id = :sessionId
            """)
    BigDecimal sumNetPnlBySessionId(UUID sessionId);

    @Query("""
            select coalesce(sum(entry.amountUsdt), 0)
            from LiveTradePnlLedger entry
            where entry.execution.id = :executionId
            """)
    BigDecimal sumNetPnlByExecutionId(UUID executionId);
}
