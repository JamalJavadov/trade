ALTER TABLE symbol_evaluation
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS snapshot_json JSONB,
    ADD COLUMN IF NOT EXISTS integrity_json JSONB,
    ADD COLUMN IF NOT EXISTS deterministic_evidence_json JSONB,
    ADD COLUMN IF NOT EXISTS validation_json JSONB,
    ADD COLUMN IF NOT EXISTS confirmation_json JSONB,
    ADD COLUMN IF NOT EXISTS ai_review_json JSONB,
    ADD COLUMN IF NOT EXISTS final_gate_json JSONB,
    ADD COLUMN IF NOT EXISTS recommendation_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS final_integrity_score INT,
    ADD COLUMN IF NOT EXISTS conflict_state VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_symbol_eval_trace_id ON symbol_evaluation(trace_id);
CREATE INDEX IF NOT EXISTS idx_symbol_eval_run_eligible ON symbol_evaluation(scan_run_id, recommendation_eligible);
CREATE INDEX IF NOT EXISTS idx_symbol_eval_run_integrity ON symbol_evaluation(scan_run_id, final_integrity_score DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS idx_symbol_eval_run_conflict ON symbol_evaluation(scan_run_id, conflict_state);

CREATE TABLE IF NOT EXISTS scan_candidate_event (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    scan_run_id UUID NOT NULL REFERENCES scan_run(id) ON DELETE CASCADE,
    symbol VARCHAR(50) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    seq INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    ts TIMESTAMP WITH TIME ZONE NOT NULL,
    payload_json JSONB,
    CONSTRAINT uq_scan_candidate_event_run_symbol_stage_seq UNIQUE (scan_run_id, symbol, stage, seq)
);

CREATE INDEX IF NOT EXISTS idx_scan_candidate_event_run_ts ON scan_candidate_event(scan_run_id, ts);
CREATE INDEX IF NOT EXISTS idx_scan_candidate_event_run_symbol ON scan_candidate_event(scan_run_id, symbol);
