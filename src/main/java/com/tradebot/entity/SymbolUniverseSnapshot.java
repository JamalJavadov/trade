package com.tradebot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "symbol_universe_snapshot")
@IdClass(SymbolUniverseSnapshotId.class)
@Data
public class SymbolUniverseSnapshot {
    @Id
    @Column(name = "scan_run_id")
    private UUID scanRunId;

    @Id
    @Column(name = "symbol")
    private String symbol;

    @Column(nullable = false)
    private Integer rank;

    @Column(precision = 30, scale = 8)
    private BigDecimal quoteVolumeUsdt;

    @Column(nullable = false)
    private Instant recordedAt;
}
