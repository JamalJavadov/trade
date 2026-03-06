CREATE TABLE IF NOT EXISTS ai_call_log (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    model_requested VARCHAR(255) NOT NULL,
    model_used VARCHAR(255),
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCESS', 'FAILED')),
    error_code VARCHAR(64),
    error_message TEXT,
    latency_ms BIGINT,
    trace_id VARCHAR(64),
    prompt_sanitized_json JSONB,
    response_sanitized_json JSONB
);

CREATE INDEX IF NOT EXISTS idx_ai_call_log_created_at ON ai_call_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ai_call_log_trace_id ON ai_call_log(trace_id);
CREATE INDEX IF NOT EXISTS idx_ai_call_log_status_created_at ON ai_call_log(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ai_call_log_task_created_at ON ai_call_log(task_type, created_at DESC);

CREATE TABLE IF NOT EXISTS demo_ai_call_log (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    model_requested VARCHAR(255) NOT NULL,
    model_used VARCHAR(255),
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCESS', 'FAILED')),
    error_code VARCHAR(64),
    error_message TEXT,
    latency_ms BIGINT,
    trace_id VARCHAR(64),
    prompt_sanitized_json JSONB,
    response_sanitized_json JSONB
);

CREATE INDEX IF NOT EXISTS idx_demo_ai_call_log_created_at ON demo_ai_call_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_demo_ai_call_log_trace_id ON demo_ai_call_log(trace_id);
CREATE INDEX IF NOT EXISTS idx_demo_ai_call_log_status_created_at ON demo_ai_call_log(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_demo_ai_call_log_task_created_at ON demo_ai_call_log(task_type, created_at DESC);
