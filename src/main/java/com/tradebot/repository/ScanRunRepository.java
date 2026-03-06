package com.tradebot.repository;

import com.tradebot.entity.ScanRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScanRunRepository extends JpaRepository<ScanRun, UUID> {
    Optional<ScanRun> findFirstByOrderByStartedAtDesc();

    Optional<ScanRun> findFirstByStatusOrderByStartedAtDesc(String status);

    Optional<ScanRun> findFirstByTriggerTypeOrderByStartedAtDesc(String triggerType);

    Optional<ScanRun> findFirstByDedupKey(String dedupKey);

    List<ScanRun> findTop10ByOrderByStartedAtDesc();

    List<ScanRun> findByStatus(String status);
}
