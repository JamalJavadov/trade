DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'budget_target_session'
          AND column_name = 'session_budget_usdt'
    ) THEN
        ALTER TABLE budget_target_session
            RENAME COLUMN session_budget_usdt TO budget_amount_usdt;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'budget_target_session'
          AND column_name = 'final_target_net_profit_usdt'
    ) THEN
        ALTER TABLE budget_target_session
            RENAME COLUMN final_target_net_profit_usdt TO target_profit_usdt;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'budget_target_session'
          AND column_name = 'active_trade_limit'
    ) THEN
        ALTER TABLE budget_target_session
            RENAME COLUMN active_trade_limit TO max_concurrent_positions;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'budget_target_session'
          AND column_name = 'completed_at'
    ) THEN
        ALTER TABLE budget_target_session
            RENAME COLUMN completed_at TO ended_at;
    END IF;
END $$;

ALTER TABLE budget_target_session
    ADD COLUMN IF NOT EXISTS unrealized_net_pnl_usdt NUMERIC(18, 8) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS active_positions_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS opened_positions_total INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS closed_positions_total INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS stop_reason VARCHAR(64),
    ADD COLUMN IF NOT EXISTS config_snapshot_json JSONB;

ALTER TABLE budget_target_session
    ALTER COLUMN started_at DROP NOT NULL;

UPDATE budget_target_session
SET budget_amount_usdt = COALESCE(budget_amount_usdt, 0),
    target_profit_usdt = COALESCE(target_profit_usdt, 0),
    realized_net_pnl_usdt = COALESCE(realized_net_pnl_usdt, 0),
    unrealized_net_pnl_usdt = COALESCE(unrealized_net_pnl_usdt, 0),
    max_concurrent_positions = COALESCE(max_concurrent_positions, 3),
    active_positions_count = COALESCE(active_positions_count, 0),
    opened_positions_total = COALESCE(opened_positions_total, 0),
    closed_positions_total = COALESCE(closed_positions_total, 0),
    stop_reason = COALESCE(stop_reason, completion_reason, last_error_code),
    updated_at = COALESCE(updated_at, NOW()),
    created_at = COALESCE(created_at, NOW());

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'live_trade_execution'
          AND column_name = 'budget_target_session_id'
    ) THEN
        ALTER TABLE live_trade_execution
            RENAME COLUMN budget_target_session_id TO session_id;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'live_trade_execution'
          AND column_name = 'execution_state'
    ) THEN
        ALTER TABLE live_trade_execution
            RENAME COLUMN execution_state TO execution_status;
    END IF;
END $$;

ALTER TABLE live_trade_execution
    ADD COLUMN IF NOT EXISTS requested_budget_slice_usdt NUMERIC(18, 8),
    ADD COLUMN IF NOT EXISTS requested_qty NUMERIC(30, 8),
    ADD COLUMN IF NOT EXISTS actual_filled_qty NUMERIC(30, 8),
    ADD COLUMN IF NOT EXISTS entry_response_json JSONB,
    ADD COLUMN IF NOT EXISTS protection_response_json JSONB,
    ADD COLUMN IF NOT EXISTS position_slot INT;

UPDATE live_trade_execution
SET requested_budget_slice_usdt = COALESCE(requested_budget_slice_usdt, reserved_margin_usdt),
    requested_qty = COALESCE(
            requested_qty,
            NULLIF(payload_snapshot_json #>> '{entryOrder,quantity}', '')::NUMERIC,
            NULLIF(preflight_json #>> '{exchangeValidation,quantity}', '')::NUMERIC
    ),
    actual_filled_qty = COALESCE(
            actual_filled_qty,
            NULLIF(exchange_response_json #>> '{entry,resolvedFilledQuantity}', '')::NUMERIC,
            NULLIF(exchange_response_json #>> '{entry,executedQty}', '')::NUMERIC
    ),
    entry_response_json = COALESCE(
            entry_response_json,
            CASE
                WHEN exchange_response_json IS NULL THEN NULL
                ELSE jsonb_strip_nulls(jsonb_build_object(
                        'legacyBackfill', TRUE,
                        'entry', exchange_response_json -> 'entry',
                        'position', exchange_response_json -> 'position'
                ))
            END
    ),
    protection_response_json = COALESCE(
            protection_response_json,
            CASE
                WHEN exchange_response_json IS NULL THEN NULL
                ELSE jsonb_strip_nulls(jsonb_build_object(
                        'legacyBackfill', TRUE,
                        'stopLoss', exchange_response_json -> 'stopLoss',
                        'takeProfit', exchange_response_json -> 'takeProfit',
                        'emergencyClose', exchange_response_json -> 'emergencyClose',
                        'protectionFailure', exchange_response_json -> 'protectionFailure'
                ))
            END
    ),
    realized_gross_pnl_usdt = COALESCE(realized_gross_pnl_usdt, 0),
    realized_fees_usdt = COALESCE(realized_fees_usdt, 0),
    realized_net_pnl_usdt = COALESCE(realized_net_pnl_usdt, 0),
    updated_at = COALESCE(updated_at, NOW()),
    created_at = COALESCE(created_at, NOW());

WITH ranked_active_executions AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY created_at ASC, id ASC) AS slot
    FROM live_trade_execution
    WHERE session_id IS NOT NULL
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
      )
)
UPDATE live_trade_execution execution
SET position_slot = ranked.slot
FROM ranked_active_executions ranked
WHERE execution.id = ranked.id
  AND ranked.slot <= 3
  AND execution.position_slot IS NULL;

WITH execution_rollups AS (
    SELECT session_id,
           COUNT(*) AS execution_count,
           COUNT(*) FILTER (
               WHERE execution_status IN (
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
               )
           ) AS active_count,
           COUNT(*) FILTER (
               WHERE actual_filled_qty IS NOT NULL
                 AND actual_filled_qty > 0
           ) AS opened_count,
           COUNT(*) FILTER (
               WHERE execution_status IN (
                    'RECONCILED',
                    'EMERGENCY_CLOSE_FILLED',
                    'FAILED',
                    'BLOCKED',
                    'DRY_RUN',
                    'EMERGENCY_CLOSE_FAILED'
               )
                 AND actual_filled_qty IS NOT NULL
                 AND actual_filled_qty > 0
           ) AS closed_count,
           COALESCE(SUM(realized_net_pnl_usdt), 0) AS realized_net,
           COALESCE(SUM(
               CASE
                   WHEN execution_status IN (
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
                   )
                       THEN COALESCE(NULLIF(exchange_response_json #>> '{position,unRealizedProfit}', '')::NUMERIC, 0)
                   ELSE 0
               END
           ), 0) AS unrealized_net
    FROM live_trade_execution
    WHERE session_id IS NOT NULL
    GROUP BY session_id
)
UPDATE budget_target_session session
SET active_positions_count = COALESCE(rollup.active_count, 0),
    opened_positions_total = COALESCE(rollup.opened_count, 0),
    closed_positions_total = COALESCE(rollup.closed_count, 0),
    realized_net_pnl_usdt = COALESCE(rollup.realized_net, 0),
    unrealized_net_pnl_usdt = COALESCE(rollup.unrealized_net, 0)
FROM execution_rollups rollup
WHERE session.id = rollup.session_id;

UPDATE budget_target_session session
SET config_snapshot_json = COALESCE(
        config_snapshot_json,
        jsonb_strip_nulls(jsonb_build_object(
                'legacyBackfill', TRUE,
                'budgetAmountUsdt', session.budget_amount_usdt,
                'targetProfitUsdt', session.target_profit_usdt,
                'maxConcurrentPositions', session.max_concurrent_positions,
                'startedBy', session.started_by,
                'stoppedBy', session.stopped_by,
                'traceId', session.trace_id
        ))
    )
WHERE config_snapshot_json IS NULL;

UPDATE budget_target_session session
SET status = CASE
        WHEN session.status = 'RUNNING' THEN 'RUNNING'
        WHEN session.status = 'STOPPING' THEN 'STOPPING'
        WHEN session.status = 'COMPLETED' AND COALESCE(session.completion_reason, session.stop_reason) = 'TARGET_REACHED'
            THEN 'TARGET_REACHED'
        WHEN session.status = 'COMPLETED' AND COALESCE(session.opened_positions_total, 0) = 0
            THEN 'CANCELLED'
        WHEN session.status = 'COMPLETED' THEN 'STOPPED'
        WHEN session.status = 'FAILED' THEN 'FAILED'
        ELSE session.status
    END,
    stop_reason = COALESCE(
            session.stop_reason,
            CASE
                WHEN session.completion_reason = 'MANUAL_OFF' THEN 'MANUAL_OFF'
                WHEN session.completion_reason = 'RUNTIME_BLOCKED' THEN 'RUNTIME_BLOCKED'
                WHEN session.completion_reason = 'FLATTEN_FAILED' THEN 'FLATTEN_FAILED'
                WHEN session.completion_reason = 'TARGET_REACHED' THEN 'TARGET_REACHED'
                WHEN session.status = 'COMPLETED' AND COALESCE(session.opened_positions_total, 0) = 0 THEN 'CANCELLED'
                WHEN session.status = 'COMPLETED' THEN 'STOPPED'
                ELSE session.last_error_code
            END
    ),
    ended_at = CASE
        WHEN session.status IN ('RUNNING', 'STOPPING') THEN session.ended_at
        ELSE COALESCE(session.ended_at, session.stop_requested_at, NOW())
    END;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'budget_target_session_event'
          AND column_name = 'message'
    ) THEN
        ALTER TABLE budget_target_session_event
            RENAME COLUMN message TO notes;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'budget_target_session_event'
          AND column_name = 'payload_json'
    ) THEN
        ALTER TABLE budget_target_session_event
            RENAME COLUMN payload_json TO after_json;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'budget_target_session_event'
          AND column_name = 'created_at'
    ) THEN
        ALTER TABLE budget_target_session_event
            RENAME COLUMN created_at TO event_ts;
    END IF;
END $$;

ALTER TABLE budget_target_session_event
    ADD COLUMN IF NOT EXISTS execution_id UUID REFERENCES live_trade_execution(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS before_json JSONB,
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(128);

UPDATE budget_target_session_event event
SET after_json = jsonb_strip_nulls(jsonb_build_object(
        'legacyBackfill', TRUE,
        'eventStatus', event_status,
        'reasonCode', reason_code,
        'payload', after_json
    )),
    trace_id = COALESCE(event.trace_id, session.trace_id),
    event_ts = COALESCE(event_ts, NOW())
FROM budget_target_session session
WHERE session.id = event.session_id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'live_trade_execution_event'
          AND column_name = 'message'
    ) THEN
        ALTER TABLE live_trade_execution_event
            RENAME COLUMN message TO notes;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'live_trade_execution_event'
          AND column_name = 'payload_json'
    ) THEN
        ALTER TABLE live_trade_execution_event
            RENAME COLUMN payload_json TO after_json;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'live_trade_execution_event'
          AND column_name = 'created_at'
    ) THEN
        ALTER TABLE live_trade_execution_event
            RENAME COLUMN created_at TO event_ts;
    END IF;
END $$;

ALTER TABLE live_trade_execution_event
    ADD COLUMN IF NOT EXISTS before_json JSONB,
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(128);

UPDATE live_trade_execution_event event
SET after_json = jsonb_strip_nulls(jsonb_build_object(
        'legacyBackfill', TRUE,
        'eventStatus', event.event_status,
        'errorCode', event.error_code,
        'payload', event.after_json
    )),
    trace_id = COALESCE(event.trace_id, execution.trace_id),
    event_ts = COALESCE(event.event_ts, NOW())
FROM live_trade_execution execution
WHERE execution.id = event.execution_id;
