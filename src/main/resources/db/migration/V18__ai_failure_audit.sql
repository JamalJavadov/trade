ALTER TABLE ai_suggestion_batch
    ADD COLUMN IF NOT EXISTS failed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS error_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS error_message TEXT,
    ADD COLUMN IF NOT EXISTS error_details_json JSONB,
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);

CREATE TABLE IF NOT EXISTS ai_provider_audit (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(255),
    prompt_text TEXT,
    response_text TEXT,
    error_code VARCHAR(64),
    http_status INT,
    trace_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    success BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_ai_provider_audit_trace_id ON ai_provider_audit(trace_id);
CREATE INDEX IF NOT EXISTS idx_ai_provider_audit_created_at ON ai_provider_audit(created_at DESC);
