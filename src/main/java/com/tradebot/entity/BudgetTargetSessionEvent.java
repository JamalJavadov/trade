package com.tradebot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "budget_target_session_event")
@Data
public class BudgetTargetSessionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private BudgetTargetSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private LiveTradeExecution execution;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_status")
    private String eventStatus;

    @Column(name = "reason_code")
    private String reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_category", nullable = false)
    private BudgetTargetAuditEventCategory eventCategory = BudgetTargetAuditEventCategory.SESSION;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private BudgetTargetAuditSeverity severity = BudgetTargetAuditSeverity.INFO;

    @Column(name = "actor", nullable = false)
    private String actor = "system";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json", columnDefinition = "jsonb")
    private String beforeJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json", columnDefinition = "jsonb")
    private String afterJson;

    @Column(name = "notes")
    private String notes;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "event_ts", nullable = false)
    private Instant eventTs;

    public String getMessage() {
        return notes;
    }

    public void setMessage(String message) {
        this.notes = message;
    }

    public String getPayloadJson() {
        return afterJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.afterJson = payloadJson;
    }

    public Instant getCreatedAt() {
        return eventTs;
    }

    public void setCreatedAt(Instant createdAt) {
        this.eventTs = createdAt;
    }
}
