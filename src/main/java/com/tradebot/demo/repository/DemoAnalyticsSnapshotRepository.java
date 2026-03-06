package com.tradebot.demo.repository;

import com.tradebot.demo.entity.DemoAnalyticsSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DemoAnalyticsSnapshotRepository extends JpaRepository<DemoAnalyticsSnapshot, UUID> {
    Optional<DemoAnalyticsSnapshot> findFirstByLookbackNAndLastTradeId(int lookbackN, UUID lastTradeId);
}
