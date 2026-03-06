ALTER TABLE app_settings
    ADD COLUMN IF NOT EXISTS ai_live_model_routing_json JSONB,
    ADD COLUMN IF NOT EXISTS ai_demo_model_routing_json JSONB;

ALTER TABLE ai_suggestion_batch
    ADD COLUMN IF NOT EXISTS model VARCHAR(255),
    ADD COLUMN IF NOT EXISTS latency_ms BIGINT,
    ADD COLUMN IF NOT EXISTS call_status VARCHAR(20);

ALTER TABLE demo_ai_suggestion_batch
    ADD COLUMN IF NOT EXISTS latency_ms BIGINT,
    ADD COLUMN IF NOT EXISTS call_status VARCHAR(20);
