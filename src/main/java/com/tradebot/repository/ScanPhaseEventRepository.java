package com.tradebot.repository;

import com.tradebot.entity.ScanPhaseEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ScanPhaseEventRepository extends JpaRepository<ScanPhaseEvent, UUID> {
    List<ScanPhaseEvent> findByScanRunIdOrderByStartedAtAsc(UUID scanRunId);
}
