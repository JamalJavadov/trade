ALTER TABLE ai_suggestion_batch
    ADD COLUMN IF NOT EXISTS error_json JSONB,
    ADD COLUMN IF NOT EXISTS meta_json JSONB;

CREATE TABLE IF NOT EXISTS ai_call_log (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    trace_id VARCHAR(64),
    task_type VARCHAR(64) NOT NULL,
    provider VARCHAR(64),
    model VARCHAR(255),
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCESS', 'FAILED')),
    latency_ms BIGINT,
    prompt_text TEXT,
    response_text TEXT,
    error_code VARCHAR(64),
    http_status INT,
    model_requested VARCHAR(255) NOT NULL,
    model_used VARCHAR(255),
    error_message TEXT,
    prompt_sanitized_json JSONB,
    response_sanitized_json JSONB
);

ALTER TABLE ai_call_log
    ADD COLUMN IF NOT EXISTS provider VARCHAR(64),
    ADD COLUMN IF NOT EXISTS model VARCHAR(255),
    ADD COLUMN IF NOT EXISTS http_status INT,
    ADD COLUMN IF NOT EXISTS prompt_text TEXT,
    ADD COLUMN IF NOT EXISTS response_text TEXT;

CREATE INDEX IF NOT EXISTS idx_ai_call_log_trace_id ON ai_call_log(trace_id);
CREATE INDEX IF NOT EXISTS idx_ai_call_log_created_at ON ai_call_log(created_at DESC);
