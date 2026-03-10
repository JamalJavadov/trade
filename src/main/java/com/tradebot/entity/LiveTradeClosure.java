package com.tradebot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
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
@Table(name = "live_trade_closure")
@Data
public class LiveTradeClosure {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false, unique = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private LiveTradeExecution execution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private BudgetTargetSession session;

    @Column(name = "close_reason")
    private String closeReason;

    @Column(name = "closed_qty", precision = 30, scale = 8)
    private BigDecimal closedQty;

    @Column(name = "closed_price", precision = 30, scale = 8)
    private BigDecimal closedPrice;

    @Column(name = "closing_client_order_id")
    private String closingClientOrderId;

    @Column(name = "closing_order_id")
    private Long closingOrderId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "final_position_snapshot_json", columnDefinition = "jsonb")
    private String finalPositionSnapshotJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "close_response_json", columnDefinition = "jsonb")
    private String closeResponseJson;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "closed_at", nullable = false)
    private Instant closedAt;
}
