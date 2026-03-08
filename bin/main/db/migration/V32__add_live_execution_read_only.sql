UPDATE control_center_state
SET config_json = jsonb_set(
        COALESCE(config_json, '{}'::jsonb),
        '{liveExecution,readOnly}',
        to_jsonb(
                CASE
                    WHEN COALESCE((config_json #>> '{permissions,live.execution.enabled}')::boolean, TRUE)
                        THEN FALSE
                    ELSE TRUE
                END
        ),
        true
    ),
    updated_at = NOW(),
    updated_by = CONCAT(COALESCE(updated_by, 'migration'), ' | live-execution-read-only'),
    version = version + 1
WHERE id = 1
  AND (
        config_json IS NULL
        OR jsonb_typeof(config_json) <> 'object'
        OR jsonb_typeof(config_json -> 'liveExecution') <> 'object'
        OR (config_json -> 'liveExecution' ? 'readOnly') = FALSE
    );
