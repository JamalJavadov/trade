-- V16__create_best_candidate_event.sql
CREATE TABLE IF NOT EXISTS best_candidate_event (
    id UUID PRIMARY KEY,
    scan_run_id UUID NOT NULL REFERENCES scan_run(id) ON DELETE CASCADE,
    ts TIMESTAMP WITH TIME ZONE NOT NULL,
    symbol VARCHAR(50) NOT NULL,
    side VARCHAR(10),
    final_score NUMERIC(10, 4),
    recommendation_id UUID,
    reason_json JSONB
);

CREATE INDEX idx_best_candidate_event_scan_run ON best_candidate_event(scan_run_id);
CREATE INDEX idx_best_candidate_event_ts ON best_candidate_event(ts);
