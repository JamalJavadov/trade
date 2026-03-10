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
@Table(name = "exchange_sync_snapshot")
@Data
public class ExchangeSyncSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private BudgetTargetSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private LiveTradeExecution execution;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "sync_type", nullable = false)
    private String syncType;

    @Column(name = "sync_status", nullable = false)
    private String syncStatus;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "divergence_detected", nullable = false)
    private boolean divergenceDetected;

    @Column(name = "requires_intervention", nullable = false)
    private boolean requiresIntervention;

    @Column(name = "open_position", nullable = false)
    private boolean openPosition;

    @Column(name = "active_open_order_count", nullable = false)
    private int activeOpenOrderCount;

    @Column(name = "active_protection_order_count", nullable = false)
    private int activeProtectionOrderCount;

    @Column(name = "stop_loss_active", nullable = false)
    private boolean stopLossActive;

    @Column(name = "take_profit_active", nullable = false)
    private boolean takeProfitActive;

    @Column(name = "emergency_close_working", nullable = false)
    private boolean emergencyCloseWorking;

    @Column(name = "emergency_close_filled", nullable = false)
    private boolean emergencyCloseFilled;

    @Column(name = "protection_triggered", nullable = false)
    private boolean protectionTriggered;

    @Column(name = "entry_order_status")
    private String entryOrderStatus;

    @Column(name = "stop_loss_status")
    private String stopLossStatus;

    @Column(name = "take_profit_status")
    private String takeProfitStatus;

    @Column(name = "emergency_close_status")
    private String emergencyCloseStatus;

    @Column(name = "position_quantity", precision = 30, scale = 8)
    private BigDecimal positionQuantity;

    @Column(name = "actual_filled_qty", precision = 30, scale = 8)
    private BigDecimal actualFilledQty;

    @Column(name = "avg_fill_price", precision = 30, scale = 8)
    private BigDecimal avgFillPrice;

    @Column(name = "entry_price", precision = 30, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "mark_price", precision = 30, scale = 8)
    private BigDecimal markPrice;

    @Column(name = "realized_gross_pnl_usdt", precision = 18, scale = 8)
    private BigDecimal realizedGrossPnlUsdt;

    @Column(name = "realized_fees_usdt", precision = 18, scale = 8)
    private BigDecimal realizedFeesUsdt;

    @Column(name = "realized_net_pnl_usdt", precision = 18, scale = 8)
    private BigDecimal realizedNetPnlUsdt;

    @Column(name = "unrealized_pnl_usdt", precision = 18, scale = 8)
    private BigDecimal unrealizedPnlUsdt;

    @Column(name = "last_successful_sync_at")
    private Instant lastSuccessfulSyncAt;

    @Column(name = "sync_completed_at", nullable = false)
    private Instant syncCompletedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_json", columnDefinition = "jsonb", nullable = false)
    private String snapshotJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
