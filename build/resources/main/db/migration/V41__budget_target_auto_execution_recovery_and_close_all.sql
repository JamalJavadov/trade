ALTER TABLE budget_target_session
    ADD COLUMN IF NOT EXISTS execution_failure_count INT NOT NULL DEFAULT 0;

UPDATE budget_target_session
SET execution_failure_count = COALESCE(execution_failure_count, 0);
