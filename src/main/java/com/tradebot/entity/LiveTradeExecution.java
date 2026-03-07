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

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_state", nullable = false)
    private LiveTradeExecutionState executionState = LiveTradeExecutionState.REQUESTED;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

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
}
