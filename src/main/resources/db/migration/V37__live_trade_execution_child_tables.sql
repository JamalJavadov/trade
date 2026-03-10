CREATE TABLE IF NOT EXISTS live_trade_order (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    execution_id UUID NOT NULL REFERENCES live_trade_execution(id) ON DELETE CASCADE,
    session_id UUID REFERENCES budget_target_session(id) ON DELETE SET NULL,
    symbol VARCHAR(50) NOT NULL,
    order_role VARCHAR(32) NOT NULL,
    client_order_id VARCHAR(128),
    exchange_order_id BIGINT,
    client_algo_id VARCHAR(128),
    exchange_algo_id BIGINT,
    requested_qty NUMERIC(30, 8),
    executed_qty NUMERIC(30, 8),
    limit_price NUMERIC(30, 8),
    trigger_price NUMERIC(30, 8),
    avg_fill_price NUMERIC(30, 8),
    order_status VARCHAR(64),
    request_json JSONB,
    response_json JSONB,
    snapshot_json JSONB,
    trace_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_live_trade_order_role CHECK (
        order_role IN ('ENTRY', 'STOP_LOSS', 'TAKE_PROFIT', 'EMERGENCY_CLOSE')
    )
);

CREATE TABLE IF NOT EXISTS live_trade_closure (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    execution_id UUID NOT NULL UNIQUE REFERENCES live_trade_execution(id) ON DELETE CASCADE,
    session_id UUID REFERENCES budget_target_session(id) ON DELETE SET NULL,
    close_reason VARCHAR(64),
    closed_qty NUMERIC(30, 8),
    closed_price NUMERIC(30, 8),
    closing_client_order_id VARCHAR(128),
    closing_order_id BIGINT,
    final_position_snapshot_json JSONB,
    close_response_json JSONB,
    trace_id VARCHAR(128),
    closed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS live_trade_pnl_ledger (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id UUID NOT NULL REFERENCES budget_target_session(id) ON DELETE CASCADE,
    execution_id UUID REFERENCES live_trade_execution(id) ON DELETE SET NULL,
    event_type VARCHAR(64) NOT NULL,
    amount_usdt NUMERIC(18, 8) NOT NULL,
    event_ts TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    source_type VARCHAR(64) NOT NULL,
    source_ref VARCHAR(255) NOT NULL,
    before_json JSONB,
    after_json JSONB,
    notes TEXT,
    trace_id VARCHAR(128)
);

CREATE TABLE IF NOT EXISTS session_symbol_decision_audit (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id UUID NOT NULL REFERENCES budget_target_session(id) ON DELETE CASCADE,
    scan_run_id UUID REFERENCES scan_run(id) ON DELETE SET NULL,
    recommendation_id UUID REFERENCES recommendation(id) ON DELETE SET NULL,
    execution_id UUID REFERENCES live_trade_execution(id) ON DELETE SET NULL,
    symbol VARCHAR(50) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    event_ts TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    before_json JSONB,
    after_json JSONB,
    notes TEXT,
    trace_id VARCHAR(128)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_live_trade_order_execution_role
    ON live_trade_order (execution_id, order_role);

CREATE UNIQUE INDEX IF NOT EXISTS uq_live_trade_pnl_ledger_source
    ON live_trade_pnl_ledger (source_type, source_ref);

WITH execution_source AS (
    SELECT id,
           session_id,
           symbol,
           trace_id,
           requested_qty,
           actual_filled_qty,
           entry_client_order_id,
           entry_order_id,
           sl_client_order_id,
           sl_order_id,
           tp_client_order_id,
           tp_order_id,
           emergency_close_client_order_id,
           emergency_close_order_id,
           entry_response_json,
           protection_response_json,
           exchange_response_json,
           payload_snapshot_json,
           execution_status,
           created_at,
           updated_at
    FROM live_trade_execution
)
INSERT INTO live_trade_order (
    execution_id,
    session_id,
    symbol,
    order_role,
    client_order_id,
    exchange_order_id,
    client_algo_id,
    exchange_algo_id,
    requested_qty,
    executed_qty,
    avg_fill_price,
    order_status,
    request_json,
    response_json,
    snapshot_json,
    trace_id,
    created_at,
    updated_at
)
SELECT source.id,
       source.session_id,
       source.symbol,
       'ENTRY',
       source.entry_client_order_id,
       source.entry_order_id,
       NULL,
       NULL,
       source.requested_qty,
       source.actual_filled_qty,
       NULLIF(source.entry_response_json #>> '{entry,resolvedAvgPrice}', '')::NUMERIC,
       COALESCE(source.entry_response_json #>> '{entry,status}', source.execution_status),
       source.payload_snapshot_json,
       source.entry_response_json,
       source.exchange_response_json,
       source.trace_id,
       source.created_at,
       source.updated_at
FROM execution_source source
WHERE source.entry_client_order_id IS NOT NULL
ON CONFLICT (execution_id, order_role) DO NOTHING;

INSERT INTO live_trade_order (
    execution_id,
    session_id,
    symbol,
    order_role,
    client_order_id,
    exchange_order_id,
    client_algo_id,
    exchange_algo_id,
    requested_qty,
    executed_qty,
    trigger_price,
    order_status,
    response_json,
    snapshot_json,
    trace_id,
    created_at,
    updated_at
)
SELECT source.id,
       source.session_id,
       source.symbol,
       'STOP_LOSS',
       NULL,
       NULL,
       source.sl_client_order_id,
       source.sl_order_id,
       NULL,
       NULL,
       NULLIF(source.protection_response_json #>> '{stopLoss,response,triggerPrice}', '')::NUMERIC,
       source.protection_response_json #>> '{stopLoss,response,algoStatus}',
       source.protection_response_json -> 'stopLoss',
       source.exchange_response_json,
       source.trace_id,
       source.created_at,
       source.updated_at
FROM live_trade_execution source
WHERE source.sl_client_order_id IS NOT NULL
ON CONFLICT (execution_id, order_role) DO NOTHING;

INSERT INTO live_trade_order (
    execution_id,
    session_id,
    symbol,
    order_role,
    client_order_id,
    exchange_order_id,
    client_algo_id,
    exchange_algo_id,
    requested_qty,
    executed_qty,
    trigger_price,
    order_status,
    response_json,
    snapshot_json,
    trace_id,
    created_at,
    updated_at
)
SELECT source.id,
       source.session_id,
       source.symbol,
       'TAKE_PROFIT',
       NULL,
       NULL,
       source.tp_client_order_id,
       source.tp_order_id,
       NULL,
       NULL,
       NULLIF(source.protection_response_json #>> '{takeProfit,response,triggerPrice}', '')::NUMERIC,
       source.protection_response_json #>> '{takeProfit,response,algoStatus}',
       source.protection_response_json -> 'takeProfit',
       source.exchange_response_json,
       source.trace_id,
       source.created_at,
       source.updated_at
FROM live_trade_execution source
WHERE source.tp_client_order_id IS NOT NULL
ON CONFLICT (execution_id, order_role) DO NOTHING;

INSERT INTO live_trade_order (
    execution_id,
    session_id,
    symbol,
    order_role,
    client_order_id,
    exchange_order_id,
    client_algo_id,
    exchange_algo_id,
    requested_qty,
    executed_qty,
    avg_fill_price,
    order_status,
    response_json,
    snapshot_json,
    trace_id,
    created_at,
    updated_at
)
SELECT source.id,
       source.session_id,
       source.symbol,
       'EMERGENCY_CLOSE',
       source.emergency_close_client_order_id,
       source.emergency_close_order_id,
       NULL,
       NULL,
       source.actual_filled_qty,
       source.actual_filled_qty,
       NULLIF(source.protection_response_json #>> '{emergencyClose,avgPrice}', '')::NUMERIC,
       source.protection_response_json #>> '{emergencyClose,status}',
       source.protection_response_json -> 'emergencyClose',
       source.exchange_response_json,
       source.trace_id,
       source.created_at,
       source.updated_at
FROM live_trade_execution source
WHERE source.emergency_close_client_order_id IS NOT NULL
ON CONFLICT (execution_id, order_role) DO NOTHING;

INSERT INTO live_trade_closure (
    execution_id,
    session_id,
    close_reason,
    closed_qty,
    closed_price,
    closing_client_order_id,
    closing_order_id,
    final_position_snapshot_json,
    close_response_json,
    trace_id,
    closed_at
)
SELECT execution.id,
       execution.session_id,
       execution.close_reason,
       execution.actual_filled_qty,
       NULLIF(execution.exchange_response_json #>> '{emergencyClose,avgPrice}', '')::NUMERIC,
       COALESCE(execution.emergency_close_client_order_id, execution.tp_client_order_id, execution.sl_client_order_id),
       COALESCE(execution.emergency_close_order_id, execution.tp_order_id, execution.sl_order_id),
       execution.exchange_response_json -> 'position',
       execution.exchange_response_json,
       execution.trace_id,
       COALESCE(execution.completed_at, execution.updated_at, execution.created_at, NOW())
FROM live_trade_execution execution
WHERE execution.actual_filled_qty IS NOT NULL
  AND execution.actual_filled_qty > 0
  AND execution.execution_status IN (
        'RECONCILED',
        'EMERGENCY_CLOSE_FILLED',
        'FAILED',
        'EMERGENCY_CLOSE_FAILED'
  )
ON CONFLICT (execution_id) DO NOTHING;

INSERT INTO live_trade_pnl_ledger (
    session_id,
    execution_id,
    event_type,
    amount_usdt,
    event_ts,
    source_type,
    source_ref,
    after_json,
    notes,
    trace_id
)
SELECT execution.session_id,
       execution.id,
       'REALIZED_GROSS_PNL',
       COALESCE(execution.realized_gross_pnl_usdt, 0),
       COALESCE(execution.completed_at, execution.updated_at, execution.created_at, NOW()),
       'LEGACY_EXECUTION_GROSS',
       execution.id::text || ':gross',
       jsonb_build_object(
           'legacyBackfill', TRUE,
           'executionStatus', execution.execution_status,
           'realizedGrossPnlUsdt', COALESCE(execution.realized_gross_pnl_usdt, 0)
       ),
       'Backfilled from legacy live_trade_execution realized gross PnL.',
       execution.trace_id
FROM live_trade_execution execution
WHERE execution.session_id IS NOT NULL
  AND COALESCE(execution.realized_gross_pnl_usdt, 0) <> 0
ON CONFLICT (source_type, source_ref) DO NOTHING;

INSERT INTO live_trade_pnl_ledger (
    session_id,
    execution_id,
    event_type,
    amount_usdt,
    event_ts,
    source_type,
    source_ref,
    after_json,
    notes,
    trace_id
)
SELECT execution.session_id,
       execution.id,
       'COMMISSION_FEE',
       COALESCE(execution.realized_fees_usdt, 0) * -1,
       COALESCE(execution.completed_at, execution.updated_at, execution.created_at, NOW()),
       'LEGACY_EXECUTION_FEE',
       execution.id::text || ':fee',
       jsonb_build_object(
           'legacyBackfill', TRUE,
           'executionStatus', execution.execution_status,
           'realizedFeesUsdt', COALESCE(execution.realized_fees_usdt, 0)
       ),
       'Backfilled from legacy live_trade_execution realized fee totals.',
       execution.trace_id
FROM live_trade_execution execution
WHERE execution.session_id IS NOT NULL
  AND COALESCE(execution.realized_fees_usdt, 0) <> 0
ON CONFLICT (source_type, source_ref) DO NOTHING;
