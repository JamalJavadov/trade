CREATE TABLE scan_phase_event (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    scan_run_id UUID NOT NULL REFERENCES scan_run(id),
    phase VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    meta_json JSONB
);

CREATE INDEX idx_scan_phase_event_scan_run ON scan_phase_event(scan_run_id);

CREATE TABLE symbol_evaluation (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    scan_run_id UUID NOT NULL REFERENCES scan_run(id),
    symbol VARCHAR(50) NOT NULL,
    rank_in_universe INT NOT NULL,
    quote_volume_usdt NUMERIC(30, 8),
    bias VARCHAR(20),
    decision VARCHAR(20) NOT NULL,
    side VARCHAR(10),
    skip_reason_code VARCHAR(50),
    skip_reason_text VARCHAR(255),
    metrics_json JSONB,
    diagnostics_json JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_symbol_evaluation_run_symbol UNIQUE (scan_run_id, symbol)
);

CREATE INDEX idx_symbol_eval_run_decision ON symbol_evaluation(scan_run_id, decision);
CREATE INDEX idx_symbol_eval_run_score ON symbol_evaluation(scan_run_id, ((metrics_json->>'final_score')::numeric) DESC NULLS LAST);
