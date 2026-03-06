package com.tradebot.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "demo_ai_suggestion_item")
@IdClass(DemoAiSuggestionItemId.class)
@Data
public class DemoAiSuggestionItem {

    @Id
    @Column(name = "batch_id")
    private UUID batchId;

    @Id
    @Column(name = "key")
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_value", columnDefinition = "jsonb")
    private String proposedValue;

    @Column(name = "reason")
    private String reason;

    @Column(name = "impact_hypothesis")
    private String impactHypothesis;

    @Column(name = "risk_of_change")
    private String riskOfChange;

    @Column(name = "status", nullable = false)
    private String status;
}
