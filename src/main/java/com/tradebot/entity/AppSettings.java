package com.tradebot.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import java.math.BigDecimal;
import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "app_settings")
@Data
public class AppSettings {
    @Id
    @Column(name = "id", nullable = false)
    private String id = "DEFAULT"; // Single row expected

    @Column(name = "safe_mode", nullable = false)
    private boolean safeMode = false;

    @Column(name = "scheduler_enabled", nullable = false)
    private boolean schedulerEnabled = true;

    @Column(name = "scan_interval_minutes", nullable = false)
    private int scanIntervalMinutes = 20;

    @Column(name = "budget_usdt", precision = 19, scale = 4)
    private BigDecimal budgetUsdt;

    @Column(name = "max_budget_pct", precision = 5, scale = 2)
    private BigDecimal maxBudgetPct = new BigDecimal("5.00");

    @Column(name = "equity_override_usdt", precision = 19, scale = 4)
    private BigDecimal equityOverrideUsdt;

    @Column(name = "max_equity_pct", precision = 5, scale = 2)
    private BigDecimal maxEquityPct = new BigDecimal("1.00");

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_live_model_routing_json", columnDefinition = "jsonb")
    private String aiLiveModelRoutingJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_demo_model_routing_json", columnDefinition = "jsonb")
    private String aiDemoModelRoutingJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", columnDefinition = "jsonb")
    private String configJson;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
