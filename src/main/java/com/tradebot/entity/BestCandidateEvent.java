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
@Table(name = "best_candidate_event")
@Data
public class BestCandidateEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "scan_run_id", nullable = false)
    private UUID scanRunId;

    @Column(nullable = false)
    private Instant ts;

    @Column(nullable = false, length = 50)
    private String symbol;

    @Column(length = 10)
    private String side;

    @Column(precision = 10, scale = 4)
    private BigDecimal finalScore;

    @Column(name = "recommendation_id")
    private UUID recommendationId;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String reasonJson;
}
