package com.tradebot.repository;

import com.tradebot.entity.ExchangeSyncSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExchangeSyncSnapshotRepository extends JpaRepository<ExchangeSyncSnapshot, UUID> {

    Optional<ExchangeSyncSnapshot> findFirstByExecution_IdOrderBySyncCompletedAtDesc(UUID executionId);

    Optional<ExchangeSyncSnapshot> findFirstByExecution_IdAndSyncStatusOrderBySyncCompletedAtDesc(
            UUID executionId,
            String syncStatus);

    List<ExchangeSyncSnapshot> findTop50ByExecution_IdOrderBySyncCompletedAtDesc(UUID executionId);

    List<ExchangeSyncSnapshot> findByExecution_IdInOrderBySyncCompletedAtDesc(Collection<UUID> executionIds);

    Optional<ExchangeSyncSnapshot> findFirstBySession_IdOrderBySyncCompletedAtDesc(UUID sessionId);

    List<ExchangeSyncSnapshot> findTop200BySession_IdOrderBySyncCompletedAtDesc(UUID sessionId);
}
