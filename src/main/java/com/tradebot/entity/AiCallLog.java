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
@Table(name = "ai_call_log")
@Data
public class AiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "task_type", nullable = false)
    private String taskType;

    @Column(name = "provider")
    private String provider;

    @Column(name = "model")
    private String model;

    @Column(name = "model_requested", nullable = false)
    private String modelRequested;

    @Column(name = "model_used")
    private String modelUsed;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prompt_sanitized_json", columnDefinition = "jsonb")
    private String promptSanitizedJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_sanitized_json", columnDefinition = "jsonb")
    private String responseSanitizedJson;

    @Column(name = "prompt_text", columnDefinition = "text")
    private String promptText;

    @Column(name = "response_text", columnDefinition = "text")
    private String responseText;
}
