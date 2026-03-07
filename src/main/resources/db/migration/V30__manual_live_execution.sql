CREATE TABLE IF NOT EXISTS live_trade_execution (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    recommendation_id UUID NOT NULL REFERENCES recommendation(id),
    trigger_mode VARCHAR(32) NOT NULL,
    symbol VARCHAR(50) NOT NULL,
    side VARCHAR(10) NOT NULL,
    operator_id VARCHAR(128),
    trace_id VARCHAR(128),
    dry_run BOOLEAN NOT NULL DEFAULT TRUE,
    payload_snapshot_json JSONB,
    preflight_json JSONB,
    exchange_response_json JSONB,
    execution_state VARCHAR(64) NOT NULL,
    error_code VARCHAR(64),
    error_message TEXT,
    entry_client_order_id VARCHAR(64),
    sl_client_order_id VARCHAR(64),
    tp_client_order_id VARCHAR(64),
    emergency_close_client_order_id VARCHAR(64),
    entry_order_id BIGINT,
    sl_order_id BIGINT,
    tp_order_id BIGINT,
    emergency_close_order_id BIGINT,
    reconcile_count INT NOT NULL DEFAULT 0,
    submitted_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_reconciled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS live_trade_execution_event (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    execution_id UUID NOT NULL REFERENCES live_trade_execution(id) ON DELETE CASCADE,
    event_type VARCHAR(64) NOT NULL,
    event_status VARCHAR(64) NOT NULL,
    message TEXT,
    error_code VARCHAR(64),
    payload_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_live_trade_execution_recommendation_created
    ON live_trade_execution (recommendation_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_live_trade_execution_symbol_created
    ON live_trade_execution (symbol, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_live_trade_execution_state_created
    ON live_trade_execution (execution_state, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_live_trade_execution_event_execution_created
    ON live_trade_execution_event (execution_id, created_at ASC);

CREATE UNIQUE INDEX IF NOT EXISTS uq_live_trade_execution_active_recommendation
    ON live_trade_execution (recommendation_id)
    WHERE execution_state IN (
        'REQUESTED',
        'SUBMITTING',
        'ENTRY_SUBMITTED',
        'PROTECTION_SUBMITTED',
        'OPEN',
        'PENDING_RECONCILE',
        'EMERGENCY_CLOSE_SUBMITTED'
    );

CREATE UNIQUE INDEX IF NOT EXISTS uq_live_trade_execution_active_symbol
    ON live_trade_execution (symbol)
    WHERE execution_state IN (
        'REQUESTED',
        'SUBMITTING',
        'ENTRY_SUBMITTED',
        'PROTECTION_SUBMITTED',
        'OPEN',
        'PENDING_RECONCILE',
        'EMERGENCY_CLOSE_SUBMITTED'
    );

INSERT INTO operator_permission (key, title, description, group_name, danger_level, enabled, updated_at, updated_by)
VALUES
    ('live.execution.view', 'View Live Executions',
     'Allows viewing live execution preflight, state, and reconciliation results.',
     'LIVE', 'LOW', true, NOW(), 'bootstrap'),
    ('live.execution.run', 'Run Live Execution',
     'Allows submitting manual live Binance Futures orders from recommendation detail.',
     'LIVE', 'HIGH', true, NOW(), 'bootstrap'),
    ('live.execution.reconcile', 'Reconcile Live Executions',
     'Allows manually reconciling live execution state against Binance order status.',
     'LIVE', 'MED', true, NOW(), 'bootstrap')
ON CONFLICT (key) DO NOTHING;
