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
@Table(name = "demo_account")
@Data
public class DemoAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "starting_balance_usdt", nullable = false, precision = 20, scale = 8)
    private BigDecimal startingBalanceUsdt;

    @Column(name = "balance_usdt", nullable = false, precision = 20, scale = 8)
    private BigDecimal balanceUsdt;

    @Column(name = "equity_usdt", nullable = false, precision = 20, scale = 8)
    private BigDecimal equityUsdt;

    @Column(name = "mode_enabled", nullable = false)
    private boolean modeEnabled;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;
}
