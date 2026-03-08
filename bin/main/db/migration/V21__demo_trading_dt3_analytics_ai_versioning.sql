CREATE TABLE IF NOT EXISTS demo_analytics_snapshot (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lookback_n INT NOT NULL CHECK (lookback_n > 0),
    summary_json JSONB NOT NULL,
    last_trade_id UUID REFERENCES demo_trade(id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_demo_analytics_snapshot_lookback_trade
    ON demo_analytics_snapshot(lookback_n, last_trade_id)
    WHERE last_trade_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_demo_analytics_snapshot_lookback_created
    ON demo_analytics_snapshot(lookback_n, created_at DESC);

CREATE TABLE IF NOT EXISTS demo_account_equity_event (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    trade_id UUID REFERENCES demo_trade(id),
    close_reason VARCHAR(24),
    balance_usdt NUMERIC(20, 8) NOT NULL,
    equity_usdt NUMERIC(20, 8) NOT NULL,
    realized_pnl_usdt NUMERIC(20, 8) NOT NULL,
    event_type VARCHAR(24) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_demo_account_equity_event_created_at
    ON demo_account_equity_event(created_at ASC);

CREATE INDEX IF NOT EXISTS idx_demo_account_equity_event_trade_id
    ON demo_account_equity_event(trade_id);

ALTER TABLE demo_ai_suggestion_batch
    ADD COLUMN IF NOT EXISTS accepted_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS accepted_by VARCHAR(120),
    ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS reject_reason TEXT,
    ADD COLUMN IF NOT EXISTS failed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS error_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_demo_ai_batch_status_created
    ON demo_ai_suggestion_batch(status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_demo_ai_batch_trace_id
    ON demo_ai_suggestion_batch(trace_id);
