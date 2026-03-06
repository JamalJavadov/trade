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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "demo_trade")
@Data
public class DemoTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "side", nullable = false)
    private String side;

    @Column(name = "leverage", nullable = false)
    private Integer leverage;

    @Column(name = "qty", nullable = false, precision = 30, scale = 8)
    private BigDecimal qty;

    @Column(name = "remaining_qty", nullable = false, precision = 30, scale = 8)
    private BigDecimal remainingQty = BigDecimal.ZERO;

    @Column(name = "entry_price", nullable = false, precision = 30, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "sl_price", nullable = false, precision = 30, scale = 8)
    private BigDecimal slPrice;

    @Column(name = "tp1_price", nullable = false, precision = 30, scale = 8)
    private BigDecimal tp1Price;

    @Column(name = "tp2_price", precision = 30, scale = 8)
    private BigDecimal tp2Price;

    @Column(name = "tp3_price", precision = 30, scale = 8)
    private BigDecimal tp3Price;

    @Column(name = "working_type", nullable = false)
    private String workingType = "MARK_PRICE";

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "close_reason")
    private String closeReason;

    @Column(name = "current_sl_price", precision = 30, scale = 8)
    private BigDecimal currentSlPrice;

    @Column(name = "stage", nullable = false)
    private Integer stage = 0;

    @Column(name = "last_mark_price", precision = 30, scale = 8)
    private BigDecimal lastMarkPrice;

    @Column(name = "risk_usdt_initial", precision = 20, scale = 8)
    private BigDecimal riskUsdtInitial;

    @Column(name = "realized_pnl_usdt", nullable = false, precision = 20, scale = 8)
    private BigDecimal realizedPnlUsdt = BigDecimal.ZERO;

    @Column(name = "entry_fee_usdt", nullable = false, precision = 20, scale = 8)
    private BigDecimal entryFeeUsdt = BigDecimal.ZERO;

    @Column(name = "exit_fee_usdt", nullable = false, precision = 20, scale = 8)
    private BigDecimal exitFeeUsdt = BigDecimal.ZERO;

    @Column(name = "total_fees_usdt", nullable = false, precision = 20, scale = 8)
    private BigDecimal totalFeesUsdt = BigDecimal.ZERO;

    @Column(name = "pnl_usdt", precision = 20, scale = 8)
    private BigDecimal pnlUsdt;

    @Column(name = "r_multiple", precision = 10, scale = 4)
    private BigDecimal rMultiple;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_json", columnDefinition = "jsonb")
    private String snapshotJson;
}
