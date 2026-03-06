ALTER TABLE scan_run
    ADD COLUMN IF NOT EXISTS trigger_type VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS dedup_key VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_code VARCHAR(64);

UPDATE scan_run
SET requested_at = COALESCE(requested_at, started_at, NOW())
WHERE requested_at IS NULL;

WITH started_rank AS (
    SELECT id,
           ROW_NUMBER() OVER (ORDER BY started_at DESC NULLS LAST, id DESC) AS row_num
    FROM scan_run
    WHERE status = 'STARTED'
)
UPDATE scan_run run
SET status = 'FAILED',
    finished_at = COALESCE(run.finished_at, NOW()),
    error_code = COALESCE(run.error_code, 'WORKER_RESTART'),
    notes = COALESCE(run.notes, 'Recovered stale STARTED run during autoscan migration.')
FROM started_rank ranked
WHERE run.id = ranked.id
  AND ranked.row_num > 1;

CREATE INDEX IF NOT EXISTS idx_scan_run_status_started_at ON scan_run (status, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_scan_run_trigger_started_at ON scan_run (trigger_type, started_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uq_scan_run_single_started
    ON scan_run ((1))
    WHERE status = 'STARTED';

CREATE UNIQUE INDEX IF NOT EXISTS uq_scan_run_dedup_key
    ON scan_run (dedup_key)
    WHERE dedup_key IS NOT NULL;
