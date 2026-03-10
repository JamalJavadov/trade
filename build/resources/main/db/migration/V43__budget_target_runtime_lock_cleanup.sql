UPDATE control_center_state
SET config_json = jsonb_set(
        jsonb_set(
            COALESCE(config_json, '{}'::jsonb),
            '{budgetTargetAutoExecution,maxConcurrentPositions}',
            to_jsonb(3),
            TRUE
        ),
        '{budgetTargetAutoExecution,allowCloseAllOnTarget}',
        to_jsonb(TRUE),
        TRUE
    ),
    updated_at = NOW(),
    updated_by = CONCAT(COALESCE(updated_by, 'migration'), ' | budget-target-runtime-lock-cleanup'),
    version = version + 1
WHERE jsonb_path_exists(COALESCE(config_json, '{}'::jsonb), '$.budgetTargetAutoExecution');

UPDATE budget_target_session
SET max_concurrent_positions = 3,
    active_positions_count = LEAST(COALESCE(active_positions_count, 0), 3),
    updated_at = NOW(),
    config_snapshot_json = jsonb_set(
        jsonb_set(
            jsonb_set(
                COALESCE(config_snapshot_json, '{}'::jsonb),
                '{maxConcurrentPositions}',
                to_jsonb(3),
                TRUE
            ),
            '{autoTargetMode,maxConcurrentPositions}',
            to_jsonb(3),
            TRUE
        ),
        '{autoTargetMode,allowCloseAllOnTarget}',
        to_jsonb(TRUE),
        TRUE
    );

WITH ranked_active_slots AS (
    SELECT execution.id,
           ROW_NUMBER() OVER (PARTITION BY execution.session_id ORDER BY execution.created_at ASC, execution.id ASC) AS slot
    FROM live_trade_execution execution
    WHERE execution.session_id IS NOT NULL
      AND execution.execution_status IN (
            'CREATED',
            'PREFLIGHT_VALIDATING',
            'ENTRY_SUBMITTING',
            'ENTRY_SUBMITTED',
            'ENTRY_FILLED',
            'PROTECTION_SUBMITTING',
            'PROTECTION_ACTIVE',
            'ACTIVE',
            'CLOSING',
            'RECONCILING'
      )
)
UPDATE live_trade_execution execution
SET position_slot = CASE
        WHEN ranked.slot <= 3 THEN ranked.slot
        ELSE NULL
    END,
    updated_at = NOW()
FROM ranked_active_slots ranked
WHERE execution.id = ranked.id;

UPDATE live_trade_execution
SET position_slot = NULL,
    updated_at = NOW()
WHERE position_slot > 3;

ALTER TABLE budget_target_session
    DROP CONSTRAINT IF EXISTS chk_budget_target_session_max_concurrent_positions_v3;

ALTER TABLE budget_target_session
    DROP CONSTRAINT IF EXISTS chk_budget_target_session_active_positions_count_v3;

ALTER TABLE live_trade_execution
    DROP CONSTRAINT IF EXISTS chk_live_trade_execution_position_slot_v3;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_budget_target_session_max_concurrent_positions_v43'
    ) THEN
        ALTER TABLE budget_target_session
            ADD CONSTRAINT chk_budget_target_session_max_concurrent_positions_v43
            CHECK (max_concurrent_positions = 3);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_budget_target_session_active_positions_count_v43'
    ) THEN
        ALTER TABLE budget_target_session
            ADD CONSTRAINT chk_budget_target_session_active_positions_count_v43
            CHECK (
                active_positions_count >= 0
                AND active_positions_count <= 3
            );
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_live_trade_execution_position_slot_v43'
    ) THEN
        ALTER TABLE live_trade_execution
            ADD CONSTRAINT chk_live_trade_execution_position_slot_v43
            CHECK (position_slot IS NULL OR position_slot BETWEEN 1 AND 3);
    END IF;
END $$;
