package com.tradebot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scan_candidate_event")
@Data
public class ScanCandidateEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "scan_run_id", nullable = false)
    private UUID scanRunId;

    @Column(nullable = false, length = 50)
    private String symbol;

    @Column(nullable = false, length = 64)
    private String stage;

    @Column(nullable = false)
    private Integer seq;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(nullable = false)
    private Instant ts;

    @Column(name = "payload_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String payloadJson;
}
