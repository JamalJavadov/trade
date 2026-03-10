ALTER TABLE budget_target_session_event
    ADD COLUMN IF NOT EXISTS event_category VARCHAR(16),
    ADD COLUMN IF NOT EXISTS severity VARCHAR(16),
    ADD COLUMN IF NOT EXISTS actor VARCHAR(128);

ALTER TABLE session_symbol_decision_audit
    ADD COLUMN IF NOT EXISTS event_category VARCHAR(16),
    ADD COLUMN IF NOT EXISTS severity VARCHAR(16),
    ADD COLUMN IF NOT EXISTS actor VARCHAR(128);

ALTER TABLE live_trade_execution_event
    ADD COLUMN IF NOT EXISTS event_category VARCHAR(16),
    ADD COLUMN IF NOT EXISTS severity VARCHAR(16),
    ADD COLUMN IF NOT EXISTS actor VARCHAR(128);

UPDATE budget_target_session_event
SET event_category = COALESCE(event_category, 'SESSION'),
    severity = COALESCE(
        severity,
        CASE
            WHEN event_type IN ('SESSION_FAILED', 'SESSION_STOPPED_WITH_ERROR', 'SESSION_RECOVERY_FAILED')
                THEN 'ERROR'
            WHEN event_type IN ('SESSION_BLOCKED', 'ACTIVE_LIMIT_REACHED', 'BANKROLL_EXHAUSTED', 'SCAN_FAILED',
                                'SESSION_EXECUTION_FAILURE', 'CLOSE_ALL_ATTEMPT')
                THEN 'WARN'
            ELSE 'INFO'
        END
    ),
    actor = COALESCE(NULLIF(actor, ''), 'system')
WHERE event_category IS NULL
   OR severity IS NULL
   OR actor IS NULL;

UPDATE session_symbol_decision_audit
SET event_category = COALESCE(event_category, 'TRADE'),
    severity = COALESCE(
        severity,
        CASE
            WHEN event_type IN ('INTAKE_REJECTED', 'BUDGET_REJECTED')
                THEN 'WARN'
            ELSE 'INFO'
        END
    ),
    actor = COALESCE(NULLIF(actor, ''), 'system')
WHERE event_category IS NULL
   OR severity IS NULL
   OR actor IS NULL;

UPDATE live_trade_execution_event
SET event_category = COALESCE(
        event_category,
        CASE
            WHEN event_type IN ('ENTRY_SUBMITTING', 'ENTRY_SUBMITTED', 'PROTECTION_SUBMITTING', 'PROTECTION_ACTIVE',
                                'ACTIVE', 'RECONCILE', 'SCHEDULED_RECONCILE', 'RECONCILING', 'CLOSING',
                                'SAFE_CLOSE_SUBMITTED', 'SAFE_CLOSE_ALREADY_SUBMITTED', 'SAFE_CLOSE_SKIPPED',
                                'SAFE_CLOSE_TIMEOUT', 'SAFE_CLOSE_FAILED', 'FAILED')
                THEN 'EXCHANGE'
            ELSE 'TRADE'
        END
    ),
    severity = COALESCE(
        severity,
        CASE
            WHEN event_type IN ('FAILED', 'SAFE_CLOSE_TIMEOUT', 'SAFE_CLOSE_FAILED')
                THEN 'ERROR'
            WHEN event_type IN ('PREFLIGHT_REJECTED', 'RECONCILE_SKIPPED', 'SAFE_CLOSE_SKIPPED',
                                'SAFE_CLOSE_ALREADY_SUBMITTED')
                THEN 'WARN'
            ELSE 'INFO'
        END
    ),
    actor = COALESCE(NULLIF(actor, ''), 'system')
WHERE event_category IS NULL
   OR severity IS NULL
   OR actor IS NULL;

ALTER TABLE budget_target_session_event
    ALTER COLUMN event_category SET NOT NULL,
    ALTER COLUMN severity SET NOT NULL,
    ALTER COLUMN actor SET NOT NULL;

ALTER TABLE session_symbol_decision_audit
    ALTER COLUMN event_category SET NOT NULL,
    ALTER COLUMN severity SET NOT NULL,
    ALTER COLUMN actor SET NOT NULL;

ALTER TABLE live_trade_execution_event
    ALTER COLUMN event_category SET NOT NULL,
    ALTER COLUMN severity SET NOT NULL,
    ALTER COLUMN actor SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_budget_target_session_event_category_v42'
    ) THEN
        ALTER TABLE budget_target_session_event
            ADD CONSTRAINT chk_budget_target_session_event_category_v42
            CHECK (event_category IN ('SESSION', 'TRADE', 'EXCHANGE'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_budget_target_session_event_severity_v42'
    ) THEN
        ALTER TABLE budget_target_session_event
            ADD CONSTRAINT chk_budget_target_session_event_severity_v42
            CHECK (severity IN ('INFO', 'WARN', 'ERROR'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_session_symbol_decision_audit_category_v42'
    ) THEN
        ALTER TABLE session_symbol_decision_audit
            ADD CONSTRAINT chk_session_symbol_decision_audit_category_v42
            CHECK (event_category IN ('SESSION', 'TRADE', 'EXCHANGE'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_session_symbol_decision_audit_severity_v42'
    ) THEN
        ALTER TABLE session_symbol_decision_audit
            ADD CONSTRAINT chk_session_symbol_decision_audit_severity_v42
            CHECK (severity IN ('INFO', 'WARN', 'ERROR'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_live_trade_execution_event_category_v42'
    ) THEN
        ALTER TABLE live_trade_execution_event
            ADD CONSTRAINT chk_live_trade_execution_event_category_v42
            CHECK (event_category IN ('SESSION', 'TRADE', 'EXCHANGE'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_live_trade_execution_event_severity_v42'
    ) THEN
        ALTER TABLE live_trade_execution_event
            ADD CONSTRAINT chk_live_trade_execution_event_severity_v42
            CHECK (severity IN ('INFO', 'WARN', 'ERROR'));
    END IF;
END $$;

INSERT INTO operator_permission (key, title, description, group_name, danger_level, enabled, updated_at, updated_by)
VALUES
    ('live.execution.auto_session.audit.view', 'View Auto Session Audit',
     'Allows viewing budget-target auto-execution audit reports, timelines, trade history, and live audit stream.',
     'LIVE', 'LOW', true, NOW(), 'migration')
ON CONFLICT (key) DO UPDATE
SET title = EXCLUDED.title,
    description = EXCLUDED.description,
    group_name = EXCLUDED.group_name,
    danger_level = EXCLUDED.danger_level;
