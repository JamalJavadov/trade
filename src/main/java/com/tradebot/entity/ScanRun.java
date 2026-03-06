package com.tradebot.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scan_run")
@Data
public class ScanRun {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    @Column(nullable = false)
    private Integer intervalMinutes;

    @Column(nullable = false)
    private Integer topN;

    @Column(nullable = false)
    private String status;

    @Column(name = "trigger_type", nullable = false)
    private String triggerType = "MANUAL";

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "dedup_key")
    private String dedupKey;

    @Column(name = "error_code")
    private String errorCode;

    private String notes;
}
