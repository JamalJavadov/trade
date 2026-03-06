package com.tradebot.repository;

import com.tradebot.entity.BestCandidateEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BestCandidateEventRepository extends JpaRepository<BestCandidateEvent, UUID> {
    List<BestCandidateEvent> findByScanRunIdOrderByTsAsc(UUID scanRunId);
}
