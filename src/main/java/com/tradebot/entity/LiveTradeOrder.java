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
@Table(name = "live_trade_order")
@Data
public class LiveTradeOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private LiveTradeExecution execution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private BudgetTargetSession session;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "order_role", nullable = false)
    private String orderRole;

    @Column(name = "client_order_id")
    private String clientOrderId;

    @Column(name = "exchange_order_id")
    private Long exchangeOrderId;

    @Column(name = "client_algo_id")
    private String clientAlgoId;

    @Column(name = "exchange_algo_id")
    private Long exchangeAlgoId;

    @Column(name = "requested_qty", precision = 30, scale = 8)
    private BigDecimal requestedQty;

    @Column(name = "executed_qty", precision = 30, scale = 8)
    private BigDecimal executedQty;

    @Column(name = "limit_price", precision = 30, scale = 8)
    private BigDecimal limitPrice;

    @Column(name = "trigger_price", precision = 30, scale = 8)
    private BigDecimal triggerPrice;

    @Column(name = "avg_fill_price", precision = 30, scale = 8)
    private BigDecimal avgFillPrice;

    @Column(name = "order_status")
    private String orderStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_json", columnDefinition = "jsonb")
    private String requestJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_json", columnDefinition = "jsonb")
    private String responseJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_json", columnDefinition = "jsonb")
    private String snapshotJson;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
