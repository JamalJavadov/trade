CREATE TABLE IF NOT EXISTS budget_target_session (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    status VARCHAR(32) NOT NULL,
    completion_reason VARCHAR(64),
    session_budget_usdt NUMERIC(18, 8) NOT NULL,
    final_target_net_profit_usdt NUMERIC(18, 8) NOT NULL,
    realized_net_pnl_usdt NUMERIC(18, 8) NOT NULL DEFAULT 0,
    active_trade_limit INT NOT NULL DEFAULT 3,
    pending_scan_run_id UUID,
    stop_requested BOOLEAN NOT NULL DEFAULT FALSE,
    stop_requested_at TIMESTAMPTZ,
    started_by VARCHAR(128),
    stopped_by VARCHAR(128),
    trace_id VARCHAR(128),
    last_error_code VARCHAR(64),
    last_error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_budget_target_session_budget_positive CHECK (session_budget_usdt > 0),
    CONSTRAINT chk_budget_target_session_target_positive CHECK (final_target_net_profit_usdt > 0),
    CONSTRAINT chk_budget_target_session_trade_limit CHECK (active_trade_limit = 3)
);

CREATE TABLE IF NOT EXISTS budget_target_session_event (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id UUID NOT NULL REFERENCES budget_target_session(id) ON DELETE CASCADE,
    event_type VARCHAR(64) NOT NULL,
    event_status VARCHAR(64) NOT NULL,
    message TEXT,
    reason_code VARCHAR(64),
    payload_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE live_trade_execution
    ADD COLUMN IF NOT EXISTS budget_target_session_id UUID REFERENCES budget_target_session(id),
    ADD COLUMN IF NOT EXISTS reserved_margin_usdt NUMERIC(18, 8),
    ADD COLUMN IF NOT EXISTS realized_gross_pnl_usdt NUMERIC(18, 8),
    ADD COLUMN IF NOT EXISTS realized_fees_usdt NUMERIC(18, 8),
    ADD COLUMN IF NOT EXISTS realized_net_pnl_usdt NUMERIC(18, 8),
    ADD COLUMN IF NOT EXISTS close_reason VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_budget_target_session_created
    ON budget_target_session (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_budget_target_session_status_created
    ON budget_target_session (status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_budget_target_session_event_session_created
    ON budget_target_session_event (session_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_live_trade_execution_budget_target_session_created
    ON live_trade_execution (budget_target_session_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_live_trade_execution_budget_target_session_state
    ON live_trade_execution (budget_target_session_id, execution_state, created_at DESC);

INSERT INTO operator_permission (key, title, description, group_name, danger_level, enabled, updated_at, updated_by)
VALUES
    ('live.execution.auto_session.manage', 'Manage Auto Session Execution',
     'Allows enabling, disabling, and supervising budget-target auto-execution sessions.',
     'LIVE', 'HIGH', true, NOW(), 'migration')
ON CONFLICT (key) DO UPDATE
SET title = EXCLUDED.title,
    description = EXCLUDED.description,
    group_name = EXCLUDED.group_name,
    danger_level = EXCLUDED.danger_level;

UPDATE control_center_state
SET config_json = jsonb_set(
        COALESCE(config_json, '{}'::jsonb),
        '{budgetTargetAutoExecution}',
        COALESCE(
                config_json -> 'budgetTargetAutoExecution',
                jsonb_build_object(
                        'enabled', FALSE,
                        'sessionBudgetUsdt', 50,
                        'finalTargetNetProfitUsdt', 10,
                        'maxActiveTrades', 3
                )
        ),
        true
    ),
    updated_at = NOW(),
    updated_by = CONCAT(COALESCE(updated_by, 'migration'), ' | budget-target-auto-execution'),
    version = version + 1
WHERE id = 1
  AND (
        config_json IS NULL
        OR jsonb_typeof(config_json) <> 'object'
        OR jsonb_typeof(config_json -> 'budgetTargetAutoExecution') <> 'object'
    );
