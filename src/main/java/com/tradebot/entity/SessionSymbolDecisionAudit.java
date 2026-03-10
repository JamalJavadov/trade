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
@Table(name = "session_symbol_decision_audit")
@Data
public class SessionSymbolDecisionAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private BudgetTargetSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_run_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ScanRun scanRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommendation_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Recommendation recommendation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private LiveTradeExecution execution;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_ts", nullable = false)
    private Instant eventTs;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_category", nullable = false)
    private BudgetTargetAuditEventCategory eventCategory = BudgetTargetAuditEventCategory.TRADE;

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
}
