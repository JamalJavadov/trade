package com.tradebot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "budget_target_session")
@Data
public class BudgetTargetSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BudgetTargetSessionStatus status = BudgetTargetSessionStatus.DRAFT;

    @Column(name = "budget_amount_usdt", nullable = false, precision = 18, scale = 8)
    private BigDecimal budgetAmountUsdt = BigDecimal.ZERO;

    @Column(name = "target_profit_usdt", nullable = false, precision = 18, scale = 8)
    private BigDecimal targetProfitUsdt = BigDecimal.ZERO;

    @Column(name = "realized_net_pnl_usdt", nullable = false, precision = 18, scale = 8)
    private BigDecimal realizedNetPnlUsdt = BigDecimal.ZERO;

    @Column(name = "unrealized_net_pnl_usdt", nullable = false, precision = 18, scale = 8)
    private BigDecimal unrealizedNetPnlUsdt = BigDecimal.ZERO;

    @Column(name = "max_concurrent_positions", nullable = false)
    private int maxConcurrentPositions = 3;

    @Column(name = "active_positions_count", nullable = false)
    private int activePositionsCount;

    @Column(name = "opened_positions_total", nullable = false)
    private int openedPositionsTotal;

    @Column(name = "closed_positions_total", nullable = false)
    private int closedPositionsTotal;

    @Column(name = "pending_scan_run_id")
    private UUID pendingScanRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_reason")
    private BudgetTargetSessionCompletionReason completionReason;

    @Column(name = "stop_requested", nullable = false)
    private boolean stopRequested;

    @Column(name = "stop_requested_at")
    private Instant stopRequestedAt;

    @Column(name = "started_by")
    private String startedBy;

    @Column(name = "stopped_by")
    private String stoppedBy;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "stop_reason")
    private String stopReason;

    @Column(name = "last_error_code")
    private String lastErrorCode;

    @Column(name = "last_error_message")
    private String lastErrorMessage;

    @Column(name = "execution_failure_count", nullable = false)
    private int executionFailureCount;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_snapshot_json", columnDefinition = "jsonb", nullable = false)
    private String configSnapshotJson;

    public BigDecimal getSessionBudgetUsdt() {
        return budgetAmountUsdt;
    }

    public void setSessionBudgetUsdt(BigDecimal sessionBudgetUsdt) {
        this.budgetAmountUsdt = sessionBudgetUsdt;
    }

    public BigDecimal getFinalTargetNetProfitUsdt() {
        return targetProfitUsdt;
    }

    public void setFinalTargetNetProfitUsdt(BigDecimal finalTargetNetProfitUsdt) {
        this.targetProfitUsdt = finalTargetNetProfitUsdt;
    }

    public int getActiveTradeLimit() {
        return maxConcurrentPositions;
    }

    public void setActiveTradeLimit(int activeTradeLimit) {
        this.maxConcurrentPositions = activeTradeLimit;
    }

    public Instant getCompletedAt() {
        return endedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.endedAt = completedAt;
    }
}
