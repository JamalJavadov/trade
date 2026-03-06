package com.tradebot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_provider_audit")
@Data
public class AiProviderAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String provider;

    private String model;

    @Column(columnDefinition = "text")
    private String promptText;

    @Column(columnDefinition = "text")
    private String responseText;

    private String errorCode;

    private Integer httpStatus;

    private String traceId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private boolean success;
}
