package com.tradebot.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_suggestion_batch")
@Data
public class AiSuggestionBatch {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Integer basedOnLastNTrades;

    private String summary;

    @Column(nullable = false)
    private String status;

    private Instant acceptedAt;
    private String acceptedBy;
    private String rejectReason;

    private Instant failedAt;

    private String errorCode;

    private String errorMessage;

    private String model;

    private Long latencyMs;

    private String callStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String errorDetailsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_json", columnDefinition = "jsonb")
    private String errorJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta_json", columnDefinition = "jsonb")
    private String metaJson;

    private String traceId;
}
