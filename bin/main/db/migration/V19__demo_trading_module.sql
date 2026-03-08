CREATE TABLE demo_account (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    starting_balance_usdt NUMERIC(20, 8) NOT NULL,
    balance_usdt NUMERIC(20, 8) NOT NULL,
    equity_usdt NUMERIC(20, 8) NOT NULL,
    last_updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE demo_trade (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    opened_at TIMESTAMP WITH TIME ZONE,
    closed_at TIMESTAMP WITH TIME ZONE,
    symbol VARCHAR(50) NOT NULL,
    side VARCHAR(10) NOT NULL CHECK (side IN ('LONG', 'SHORT')),
    leverage INT NOT NULL,
    qty NUMERIC(30, 8) NOT NULL,
    entry_price NUMERIC(30, 8) NOT NULL,
    sl_price NUMERIC(30, 8) NOT NULL,
    tp1_price NUMERIC(30, 8) NOT NULL,
    tp2_price NUMERIC(30, 8),
    tp3_price NUMERIC(30, 8),
    working_type VARCHAR(50) NOT NULL DEFAULT 'MARK_PRICE' CHECK (working_type = 'MARK_PRICE'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'CLOSED', 'CANCELLED')),
    close_reason VARCHAR(20) CHECK (close_reason IN ('TP1', 'TP2', 'TP3', 'SL', 'TIME_STOP', 'MANUAL_CANCEL')),
    pnl_usdt NUMERIC(20, 8),
    r_multiple NUMERIC(10, 4),
    snapshot_json JSONB
);

CREATE INDEX idx_demo_trade_status ON demo_trade(status);
CREATE INDEX idx_demo_trade_symbol ON demo_trade(symbol);
CREATE INDEX idx_demo_trade_opened_at_desc ON demo_trade(opened_at DESC);

CREATE TABLE demo_run (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('STARTED', 'FINISHED', 'FAILED')),
    scan_run_id UUID REFERENCES scan_run(id),
    notes TEXT
);

CREATE TABLE demo_ai_suggestion_batch (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    based_on_last_n_trades INT NOT NULL DEFAULT 10 CHECK (based_on_last_n_trades > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PROPOSED', 'ACCEPTED', 'REJECTED', 'FAILED')),
    summary TEXT,
    model VARCHAR(255),
    prompt_json JSONB,
    response_json JSONB,
    error_json JSONB
);

CREATE TABLE demo_ai_suggestion_item (
    batch_id UUID NOT NULL REFERENCES demo_ai_suggestion_batch(id) ON DELETE CASCADE,
    key VARCHAR(120) NOT NULL,
    proposed_value JSONB,
    reason TEXT,
    impact_hypothesis TEXT,
    risk_of_change VARCHAR(10) CHECK (risk_of_change IN ('low', 'medium', 'high')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PROPOSED', 'ACCEPTED', 'REJECTED')),
    PRIMARY KEY (batch_id, key)
);

CREATE TABLE demo_strategy_config_version (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    version INT NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    config_json JSONB NOT NULL,
    change_reason TEXT
);

CREATE UNIQUE INDEX idx_demo_strategy_config_active
    ON demo_strategy_config_version(active)
    WHERE active = true;
