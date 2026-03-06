CREATE UNIQUE INDEX idx_strategy_config_active ON strategy_config_version (active) WHERE active = true;

ALTER TABLE ai_suggestion_batch ADD COLUMN accepted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE ai_suggestion_batch ADD COLUMN accepted_by VARCHAR(255);
ALTER TABLE ai_suggestion_batch ADD COLUMN reject_reason TEXT;

ALTER TABLE recommendation ADD COLUMN diagnostics_json JSONB;
