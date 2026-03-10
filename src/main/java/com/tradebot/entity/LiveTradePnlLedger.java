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
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "live_trade_pnl_ledger")
@Data
public class LiveTradePnlLedger {

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

    @Column(name = "amount_usdt", nullable = false, precision = 18, scale = 8)
    private BigDecimal amountUsdt;

    @Column(name = "event_ts", nullable = false)
    private Instant eventTs;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "source_ref", nullable = false)
    private String sourceRef;

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
