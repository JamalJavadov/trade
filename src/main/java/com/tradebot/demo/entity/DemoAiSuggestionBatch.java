package com.tradebot.demo.entity;

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
@Table(name = "demo_ai_suggestion_batch")
@Data
public class DemoAiSuggestionBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "based_on_last_n_trades", nullable = false)
    private Integer basedOnLastNTrades = 10;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "summary")
    private String summary;

    @Column(name = "model")
    private String model;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prompt_json", columnDefinition = "jsonb")
    private String promptJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_json", columnDefinition = "jsonb")
    private String responseJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_json", columnDefinition = "jsonb")
    private String errorJson;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_by")
    private String acceptedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "call_status")
    private String callStatus;
}
