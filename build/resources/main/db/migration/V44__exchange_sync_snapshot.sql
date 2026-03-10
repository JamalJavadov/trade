CREATE TABLE IF NOT EXISTS exchange_sync_snapshot (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id UUID REFERENCES budget_target_session (id) ON DELETE SET NULL,
    execution_id UUID NOT NULL REFERENCES live_trade_execution (id) ON DELETE CASCADE,
    symbol VARCHAR(64) NOT NULL,
    sync_type VARCHAR(32) NOT NULL,
    sync_status VARCHAR(32) NOT NULL,
    trace_id VARCHAR(128),
    error_code VARCHAR(128),
    error_message TEXT,
    divergence_detected BOOLEAN NOT NULL DEFAULT FALSE,
    requires_intervention BOOLEAN NOT NULL DEFAULT FALSE,
    open_position BOOLEAN NOT NULL DEFAULT FALSE,
    active_open_order_count INTEGER NOT NULL DEFAULT 0,
    active_protection_order_count INTEGER NOT NULL DEFAULT 0,
    stop_loss_active BOOLEAN NOT NULL DEFAULT FALSE,
    take_profit_active BOOLEAN NOT NULL DEFAULT FALSE,
    emergency_close_working BOOLEAN NOT NULL DEFAULT FALSE,
    emergency_close_filled BOOLEAN NOT NULL DEFAULT FALSE,
    protection_triggered BOOLEAN NOT NULL DEFAULT FALSE,
    entry_order_status VARCHAR(64),
    stop_loss_status VARCHAR(64),
    take_profit_status VARCHAR(64),
    emergency_close_status VARCHAR(64),
    position_quantity NUMERIC(30, 8),
    actual_filled_qty NUMERIC(30, 8),
    avg_fill_price NUMERIC(30, 8),
    entry_price NUMERIC(30, 8),
    mark_price NUMERIC(30, 8),
    realized_gross_pnl_usdt NUMERIC(18, 8),
    realized_fees_usdt NUMERIC(18, 8),
    realized_net_pnl_usdt NUMERIC(18, 8),
    unrealized_pnl_usdt NUMERIC(18, 8),
    last_successful_sync_at TIMESTAMPTZ,
    sync_completed_at TIMESTAMPTZ NOT NULL,
    snapshot_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_exchange_sync_snapshot_execution_ts
    ON exchange_sync_snapshot (execution_id, sync_completed_at DESC);

CREATE INDEX IF NOT EXISTS idx_exchange_sync_snapshot_session_ts
    ON exchange_sync_snapshot (session_id, sync_completed_at DESC);

CREATE INDEX IF NOT EXISTS idx_exchange_sync_snapshot_session_status
    ON exchange_sync_snapshot (session_id, sync_status, sync_completed_at DESC);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_exchange_sync_snapshot_sync_status_v44'
    ) THEN
        ALTER TABLE exchange_sync_snapshot
            ADD CONSTRAINT chk_exchange_sync_snapshot_sync_status_v44
            CHECK (sync_status IN ('SUCCESS', 'FAILURE', 'SKIPPED'));
    END IF;
END $$;
