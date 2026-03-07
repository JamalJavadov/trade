ALTER TABLE live_trade_execution
    ADD COLUMN IF NOT EXISTS client_request_id UUID;

ALTER TABLE live_trade_execution
    ALTER COLUMN dry_run SET DEFAULT FALSE;

UPDATE live_trade_execution
SET client_request_id = id
WHERE client_request_id IS NULL;

ALTER TABLE live_trade_execution
    ALTER COLUMN client_request_id SET NOT NULL;

DROP INDEX IF EXISTS uq_live_trade_execution_active_symbol;
DROP INDEX IF EXISTS uq_live_trade_execution_active_recommendation;

CREATE UNIQUE INDEX IF NOT EXISTS uq_live_trade_execution_request
    ON live_trade_execution (recommendation_id, client_request_id);

DELETE FROM operator_permission
WHERE key IN ('live.execution.view', 'live.execution.run', 'live.execution.reconcile');

INSERT INTO operator_permission (key, title, description, group_name, danger_level, enabled, updated_at, updated_by)
VALUES
    ('live.execution.enabled', 'Enable Live Execution',
     'Allows manual Binance Futures order execution from recommendation detail.',
     'LIVE', 'HIGH', true, NOW(), 'migration')
ON CONFLICT (key) DO UPDATE
SET title = EXCLUDED.title,
    description = EXCLUDED.description,
    group_name = EXCLUDED.group_name,
    danger_level = EXCLUDED.danger_level;

UPDATE control_center_state
SET config_json = (
        ((jsonb_set(
                config_json - 'liveTrading',
                '{permissions,live.execution.enabled}',
                to_jsonb(COALESCE(
                        NULLIF(config_json #>> '{permissions,live.execution.run}', '')::boolean,
                        NULLIF(config_json #>> '{permissions,live.execution.enabled}', '')::boolean,
                        NULLIF(config_json #>> '{permissions,live.execution.view}', '')::boolean,
                        NULLIF(config_json #>> '{permissions,live.execution.reconcile}', '')::boolean,
                        TRUE
                )),
                true
        ) #- '{permissions,live.execution.view}')
          #- '{permissions,live.execution.run}')
          #- '{permissions,live.execution.reconcile}'
    ),
    updated_at = NOW(),
    updated_by = CONCAT(COALESCE(updated_by, 'migration'), ' | live-execution-simplify'),
    version = version + 1
WHERE id = 1;
