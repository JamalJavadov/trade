package com.tradebot.repository;

import com.tradebot.entity.ScanCandidateEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScanCandidateEventRepository extends JpaRepository<ScanCandidateEvent, UUID> {

    List<ScanCandidateEvent> findByScanRunIdOrderByTsAsc(UUID scanRunId);

    List<ScanCandidateEvent> findByScanRunIdAndSymbolOrderByTsAsc(UUID scanRunId, String symbol);
}
