package com.tradebot.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "demo_account_equity_event")
@Data
public class DemoAccountEquityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "trade_id")
    private UUID tradeId;

    @Column(name = "close_reason")
    private String closeReason;

    @Column(name = "balance_usdt", nullable = false, precision = 20, scale = 8)
    private BigDecimal balanceUsdt;

    @Column(name = "equity_usdt", nullable = false, precision = 20, scale = 8)
    private BigDecimal equityUsdt;

    @Column(name = "realized_pnl_usdt", nullable = false, precision = 20, scale = 8)
    private BigDecimal realizedPnlUsdt;

    @Column(name = "event_type", nullable = false)
    private String eventType;
}
