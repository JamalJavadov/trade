UPDATE control_center_state
SET config_json = jsonb_set(
        COALESCE(config_json, '{}'::jsonb),
        '{budgetTargetAutoExecution}',
        jsonb_strip_nulls(jsonb_build_object(
                'enabled', COALESCE(
                        NULLIF(config_json #>> '{budgetTargetAutoExecution,enabled}', '')::BOOLEAN,
                        FALSE
                ),
                'armed', COALESCE(
                        NULLIF(config_json #>> '{budgetTargetAutoExecution,armed}', '')::BOOLEAN,
                        NULLIF(config_json #>> '{budgetTargetAutoExecution,enabled}', '')::BOOLEAN,
                        FALSE
                ),
                'readOnly', COALESCE(
                        NULLIF(config_json #>> '{budgetTargetAutoExecution,readOnly}', '')::BOOLEAN,
                        FALSE
                ),
                'maxConcurrentPositions', COALESCE(
                        NULLIF(config_json #>> '{budgetTargetAutoExecution,maxConcurrentPositions}', '')::INT,
                        NULLIF(config_json #>> '{budgetTargetAutoExecution,maxActiveTrades}', '')::INT,
                        3
                ),
                'defaultBudgetUsdt', COALESCE(
                        NULLIF(config_json #>> '{budgetTargetAutoExecution,defaultBudgetUsdt}', '')::NUMERIC,
                        NULLIF(config_json #>> '{budgetTargetAutoExecution,sessionBudgetUsdt}', '')::NUMERIC,
                        50
                ),
                'defaultTargetProfitUsdt', COALESCE(
                        NULLIF(config_json #>> '{budgetTargetAutoExecution,defaultTargetProfitUsdt}', '')::NUMERIC,
                        NULLIF(config_json #>> '{budgetTargetAutoExecution,finalTargetNetProfitUsdt}', '')::NUMERIC,
                        10
                ),
                'allowNewSessionStart', COALESCE(
                        NULLIF(config_json #>> '{budgetTargetAutoExecution,allowNewSessionStart}', '')::BOOLEAN,
                        TRUE
                ),
                'allowCloseAllOnTarget', COALESCE(
                        NULLIF(config_json #>> '{budgetTargetAutoExecution,allowCloseAllOnTarget}', '')::BOOLEAN,
                        TRUE
                ),
                'killSwitch', COALESCE(
                        NULLIF(config_json #>> '{budgetTargetAutoExecution,killSwitch}', '')::BOOLEAN,
                        FALSE
                ),
                'requireBinanceHealthPass', COALESCE(
                        NULLIF(config_json #>> '{budgetTargetAutoExecution,requireBinanceHealthPass}', '')::BOOLEAN,
                        TRUE
                ),
                'requireOperatorConfirmationForStop', COALESCE(
                        NULLIF(config_json #>> '{budgetTargetAutoExecution,requireOperatorConfirmationForStop}', '')::BOOLEAN,
                        TRUE
                ),
                'sessionTimeoutMinutes', COALESCE(
                        NULLIF(config_json #>> '{budgetTargetAutoExecution,sessionTimeoutMinutes}', '')::INT,
                        240
                )
        )),
        TRUE
    ),
    updated_at = NOW(),
    updated_by = CONCAT(COALESCE(updated_by, 'migration'), ' | budget-target-runtime-control-center'),
    version = version + 1
WHERE id = 1;

UPDATE budget_target_session
SET completion_reason = COALESCE(
        completion_reason,
        CASE stop_reason
            WHEN 'TARGET_REACHED' THEN 'TARGET_REACHED'
            WHEN 'MANUAL_OFF' THEN 'MANUAL_OFF'
            WHEN 'RUNTIME_BLOCKED' THEN 'RUNTIME_BLOCKED'
            WHEN 'KILL_SWITCH' THEN 'KILL_SWITCH'
            WHEN 'SESSION_TIMEOUT' THEN 'SESSION_TIMEOUT'
            WHEN 'FLATTEN_FAILED' THEN 'FLATTEN_FAILED'
            ELSE completion_reason
        END
    );

UPDATE budget_target_session
SET status = 'STOPPED'
WHERE status = 'TARGET_REACHED';

ALTER TABLE budget_target_session
    DROP CONSTRAINT IF EXISTS chk_budget_target_session_trade_limit;

ALTER TABLE budget_target_session
    DROP CONSTRAINT IF EXISTS chk_budget_target_session_max_concurrent_positions_v2;

ALTER TABLE budget_target_session
    DROP CONSTRAINT IF EXISTS chk_budget_target_session_active_positions_count_v2;

ALTER TABLE live_trade_execution
    DROP CONSTRAINT IF EXISTS chk_live_trade_execution_position_slot_v2;

ALTER TABLE budget_target_session
    ADD CONSTRAINT chk_budget_target_session_max_concurrent_positions_v3
    CHECK (max_concurrent_positions >= 1);

ALTER TABLE budget_target_session
    ADD CONSTRAINT chk_budget_target_session_active_positions_count_v3
    CHECK (
        active_positions_count >= 0
        AND active_positions_count <= max_concurrent_positions
    );

ALTER TABLE live_trade_execution
    ADD CONSTRAINT chk_live_trade_execution_position_slot_v3
    CHECK (position_slot IS NULL OR position_slot >= 1);

DROP INDEX IF EXISTS uq_budget_target_session_single_active;

CREATE UNIQUE INDEX uq_budget_target_session_single_active
    ON budget_target_session ((TRUE))
    WHERE status IN ('DRAFT', 'ARMED', 'RUNNING', 'TARGET_REACHED', 'STOPPING');
