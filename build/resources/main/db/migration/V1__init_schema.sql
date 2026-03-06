CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE scan_run (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    interval_minutes INT NOT NULL,
    top_n INT NOT NULL,
    status VARCHAR(50) NOT NULL,
    notes TEXT
);

CREATE TABLE symbol_universe_snapshot (
    scan_run_id UUID NOT NULL REFERENCES scan_run(id),
    symbol VARCHAR(50) NOT NULL,
    rank INT NOT NULL,
    quote_volume_usdt DECIMAL(30, 8),
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (scan_run_id, symbol)
);

CREATE TABLE recommendation (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    scan_run_id UUID NOT NULL REFERENCES scan_run(id),
    symbol VARCHAR(50) NOT NULL,
    side VARCHAR(10) NOT NULL,
    rationale_text TEXT,
    confidence_score DECIMAL(5, 2),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(50) NOT NULL
);

CREATE TABLE order_fields (
    recommendation_id UUID PRIMARY KEY REFERENCES recommendation(id),
    entry_order_json JSONB,
    sl_order_json JSONB,
    tp_order_json JSONB,
    leverage_recommendation INT,
    position_mode VARCHAR(50) DEFAULT 'ONE_WAY',
    margin_mode VARCHAR(50) DEFAULT 'ISOLATED',
    working_type VARCHAR(50) DEFAULT 'MARK_PRICE'
);

CREATE TABLE trade_execution_feedback (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    recommendation_id UUID NOT NULL REFERENCES recommendation(id),
    user_label VARCHAR(50) NOT NULL,
    pnl_usdt DECIMAL(20, 8),
    r_multiple DECIMAL(10, 4),
    notes TEXT,
    closed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE ai_suggestion_batch (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    based_on_last_n_trades INT NOT NULL,
    summary TEXT,
    status VARCHAR(50) NOT NULL
);

CREATE TABLE ai_suggestion_item (
    batch_id UUID NOT NULL REFERENCES ai_suggestion_batch(id),
    key VARCHAR(100) NOT NULL,
    proposed_value VARCHAR(255),
    reason TEXT,
    impact_hypothesis TEXT,
    status VARCHAR(50),
    PRIMARY KEY (batch_id, key)
);

CREATE TABLE strategy_config_version (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    version INT NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    config_json JSONB NOT NULL,
    change_reason TEXT
);
