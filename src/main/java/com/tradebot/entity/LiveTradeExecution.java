package com.tradebot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "live_trade_execution")
@Data
public class LiveTradeExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "recommendation_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Recommendation recommendation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private BudgetTargetSession session;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_mode", nullable = false)
    private LiveTradeTriggerMode triggerMode = LiveTradeTriggerMode.MANUAL_BUTTON;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String side;

    @Column(name = "operator_id")
    private String operatorId;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "client_request_id")
    private UUID clientRequestId;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_snapshot_json", columnDefinition = "jsonb")
    private String payloadSnapshotJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preflight_json", columnDefinition = "jsonb")
    private String preflightJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exchange_response_json", columnDefinition = "jsonb")
    private String exchangeResponseJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "entry_response_json", columnDefinition = "jsonb")
    private String entryResponseJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "protection_response_json", columnDefinition = "jsonb")
    private String protectionResponseJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", nullable = false)
    private LiveTradeExecutionState executionStatus = LiveTradeExecutionState.CREATED;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_details_json", columnDefinition = "jsonb")
    private String errorDetailsJson;

    @Column(name = "requires_intervention", nullable = false)
    private boolean requiresIntervention;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "critical_issue_json", columnDefinition = "jsonb")
    private String criticalIssueJson;

    @Column(name = "reserved_margin_usdt", precision = 18, scale = 8)
    private BigDecimal reservedMarginUsdt;

    @Column(name = "requested_budget_slice_usdt", precision = 18, scale = 8)
    private BigDecimal requestedBudgetSliceUsdt;

    @Column(name = "requested_qty", precision = 30, scale = 8)
    private BigDecimal requestedQty;

    @Column(name = "actual_filled_qty", precision = 30, scale = 8)
    private BigDecimal actualFilledQty;

    @Column(name = "realized_gross_pnl_usdt", precision = 18, scale = 8)
    private BigDecimal realizedGrossPnlUsdt;

    @Column(name = "realized_fees_usdt", precision = 18, scale = 8)
    private BigDecimal realizedFeesUsdt;

    @Column(name = "realized_net_pnl_usdt", precision = 18, scale = 8)
    private BigDecimal realizedNetPnlUsdt;

    @Column(name = "close_reason")
    private String closeReason;

    @Column(name = "entry_client_order_id")
    private String entryClientOrderId;

    @Column(name = "sl_client_order_id")
    private String slClientOrderId;

    @Column(name = "tp_client_order_id")
    private String tpClientOrderId;

    @Column(name = "emergency_close_client_order_id")
    private String emergencyCloseClientOrderId;

    @Column(name = "entry_order_id")
    private Long entryOrderId;

    @Column(name = "sl_order_id")
    private Long slOrderId;

    @Column(name = "tp_order_id")
    private Long tpOrderId;

    @Column(name = "emergency_close_order_id")
    private Long emergencyCloseOrderId;

    @Column(name = "position_slot")
    private Integer positionSlot;

    @Column(name = "reconcile_count", nullable = false)
    private int reconcileCount;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_reconciled_at")
    private Instant lastReconciledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public BudgetTargetSession getBudgetTargetSession() {
        return session;
    }

    public void setBudgetTargetSession(BudgetTargetSession budgetTargetSession) {
        this.session = budgetTargetSession;
    }

    public LiveTradeExecutionState getExecutionState() {
        return executionStatus;
    }

    public void setExecutionState(LiveTradeExecutionState executionState) {
        this.executionStatus = executionState;
    }
}
