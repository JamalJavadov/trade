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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "symbol_evaluation")
@Data
public class SymbolEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "scan_run_id", nullable = false)
    private UUID scanRunId;

    @Column(nullable = false, length = 50)
    private String symbol;

    @Column(nullable = false)
    private Integer rankInUniverse;

    private BigDecimal quoteVolumeUsdt;

    @Column(length = 20)
    private String bias;

    @Column(nullable = false, length = 20)
    private String decision;

    @Column(length = 10)
    private String side;

    @Column(length = 50)
    private String skipReasonCode;

    @Column(length = 255)
    private String skipReasonText;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metricsJson;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String diagnosticsJson;

    @Column(name = "trace_id", length = 128)
    private String traceId;

    @Column(name = "snapshot_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String snapshotJson;

    @Column(name = "integrity_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String integrityJson;

    @Column(name = "deterministic_evidence_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String deterministicEvidenceJson;

    @Column(name = "validation_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String validationJson;

    @Column(name = "confirmation_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String confirmationJson;

    @Column(name = "ai_review_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String aiReviewJson;

    @Column(name = "final_gate_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String finalGateJson;

    @Column(name = "recommendation_eligible")
    private Boolean recommendationEligible;

    @Column(name = "final_integrity_score")
    private Integer finalIntegrityScore;

    @Column(name = "conflict_state", length = 64)
    private String conflictState;

    @Column(nullable = false)
    private Instant createdAt;
}
