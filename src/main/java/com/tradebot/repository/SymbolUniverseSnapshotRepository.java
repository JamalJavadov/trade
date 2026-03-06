package com.tradebot.repository;

import com.tradebot.entity.SymbolUniverseSnapshot;
import com.tradebot.entity.SymbolUniverseSnapshotId;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface SymbolUniverseSnapshotRepository
        extends JpaRepository<SymbolUniverseSnapshot, SymbolUniverseSnapshotId> {
}
