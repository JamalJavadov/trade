ALTER TABLE budget_target_session
    ALTER COLUMN budget_amount_usdt SET NOT NULL,
    ALTER COLUMN target_profit_usdt SET NOT NULL,
    ALTER COLUMN realized_net_pnl_usdt SET NOT NULL,
    ALTER COLUMN unrealized_net_pnl_usdt SET NOT NULL,
    ALTER COLUMN max_concurrent_positions SET NOT NULL,
    ALTER COLUMN active_positions_count SET NOT NULL,
    ALTER COLUMN opened_positions_total SET NOT NULL,
    ALTER COLUMN closed_positions_total SET NOT NULL,
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL,
    ALTER COLUMN config_snapshot_json SET NOT NULL;

ALTER TABLE live_trade_execution
    ALTER COLUMN execution_status SET NOT NULL,
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE budget_target_session_event
    ALTER COLUMN event_ts SET NOT NULL;

ALTER TABLE live_trade_execution_event
    ALTER COLUMN event_ts SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_budget_target_session_status_v2'
    ) THEN
        ALTER TABLE budget_target_session
            ADD CONSTRAINT chk_budget_target_session_status_v2 CHECK (
                status IN ('DRAFT', 'ARMED', 'RUNNING', 'TARGET_REACHED', 'STOPPING', 'STOPPED', 'FAILED', 'CANCELLED')
            );
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_budget_target_session_budget_amount_positive_v2'
    ) THEN
        ALTER TABLE budget_target_session
            ADD CONSTRAINT chk_budget_target_session_budget_amount_positive_v2 CHECK (budget_amount_usdt > 0);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_budget_target_session_target_profit_positive_v2'
    ) THEN
        ALTER TABLE budget_target_session
            ADD CONSTRAINT chk_budget_target_session_target_profit_positive_v2 CHECK (target_profit_usdt > 0);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_budget_target_session_max_concurrent_positions_v2'
    ) THEN
        ALTER TABLE budget_target_session
            ADD CONSTRAINT chk_budget_target_session_max_concurrent_positions_v2 CHECK (max_concurrent_positions = 3);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_budget_target_session_active_positions_count_v2'
    ) THEN
        ALTER TABLE budget_target_session
            ADD CONSTRAINT chk_budget_target_session_active_positions_count_v2 CHECK (
                active_positions_count >= 0
                AND active_positions_count <= 3
            );
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_budget_target_session_position_totals_v2'
    ) THEN
        ALTER TABLE budget_target_session
            ADD CONSTRAINT chk_budget_target_session_position_totals_v2 CHECK (
                opened_positions_total >= 0
                AND closed_positions_total >= 0
                AND closed_positions_total <= opened_positions_total
            );
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_live_trade_execution_position_slot_v2'
    ) THEN
        ALTER TABLE live_trade_execution
            ADD CONSTRAINT chk_live_trade_execution_position_slot_v2 CHECK (
                position_slot IS NULL OR position_slot BETWEEN 1 AND 3
            );
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_budget_target_session_single_active
    ON budget_target_session ((TRUE))
    WHERE status IN ('ARMED', 'RUNNING', 'STOPPING');

CREATE INDEX IF NOT EXISTS idx_budget_target_session_status_created_v2
    ON budget_target_session (status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_budget_target_session_timeline_v2
    ON budget_target_session (created_at DESC, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_budget_target_session_event_session_ts_v2
    ON budget_target_session_event (session_id, event_ts DESC);

CREATE INDEX IF NOT EXISTS idx_live_trade_execution_session_created_v2
    ON live_trade_execution (session_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_live_trade_execution_session_status_created_v2
    ON live_trade_execution (session_id, execution_status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_live_trade_execution_symbol_created_v2
    ON live_trade_execution (symbol, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_live_trade_execution_symbol_status_created_v2
    ON live_trade_execution (symbol, execution_status, created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uq_live_trade_execution_active_position_slot
    ON live_trade_execution (session_id, position_slot)
    WHERE session_id IS NOT NULL
      AND position_slot IS NOT NULL
      AND execution_status IN (
            'REQUESTED',
            'SUBMITTING',
            'ENTRY_SUBMITTED',
            'ENTRY_PARTIALLY_FILLED',
            'ENTRY_FILLED',
            'PROTECTION_SUBMITTING',
            'PROTECTION_SUBMITTED',
            'PROTECTION_ACTIVE',
            'OPEN',
            'RECONCILING',
            'PENDING_RECONCILE',
            'PROTECTION_FAILED',
            'EMERGENCY_CLOSE_SUBMITTED'
      );

CREATE INDEX IF NOT EXISTS idx_live_trade_execution_event_timeline_v2
    ON live_trade_execution_event (execution_id, event_ts ASC);

CREATE INDEX IF NOT EXISTS idx_live_trade_order_session_created_v2
    ON live_trade_order (session_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_live_trade_order_execution_created_v2
    ON live_trade_order (execution_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_live_trade_closure_session_closed_v2
    ON live_trade_closure (session_id, closed_at DESC);

CREATE INDEX IF NOT EXISTS idx_live_trade_pnl_ledger_session_ts_v2
    ON live_trade_pnl_ledger (session_id, event_ts DESC);

CREATE INDEX IF NOT EXISTS idx_live_trade_pnl_ledger_execution_ts_v2
    ON live_trade_pnl_ledger (execution_id, event_ts DESC);

CREATE INDEX IF NOT EXISTS idx_session_symbol_decision_audit_session_symbol_ts_v2
    ON session_symbol_decision_audit (session_id, symbol, event_ts DESC);

CREATE INDEX IF NOT EXISTS idx_session_symbol_decision_audit_session_ts_v2
    ON session_symbol_decision_audit (session_id, event_ts DESC);
