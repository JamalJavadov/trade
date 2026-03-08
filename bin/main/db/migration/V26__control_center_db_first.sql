ALTER TABLE app_settings
    ADD COLUMN IF NOT EXISTS config_json JSONB,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

INSERT INTO app_settings (id, safe_mode, scheduler_enabled, scan_interval_minutes, updated_at)
VALUES ('DEFAULT', false, true, 20, NOW())
ON CONFLICT (id) DO UPDATE
SET updated_at = COALESCE(app_settings.updated_at, EXCLUDED.updated_at);

INSERT INTO operator_permission (key, title, description, group_name, danger_level, enabled, updated_at, updated_by)
VALUES
    ('scan.run_once', 'Run Scan Once',
     'Allows running one immediate scan cycle from the UI without enabling autoscan.',
     'SCAN', 'MED', true, NOW(), 'bootstrap'),
    ('scan.autoscan.toggle', 'Toggle Autoscan',
     'Allows enabling or disabling scheduled autoscan execution in runtime.',
     'SCAN', 'HIGH', true, NOW(), 'bootstrap'),
    ('scan.stream.view', 'View Scan Stream',
     'Allows viewing live scan stream updates and progress events.',
     'SCAN', 'LOW', true, NOW(), 'bootstrap'),
    ('settings.update', 'Update Settings',
     'Allows updating operational runtime settings in the control center.',
     'SETTINGS', 'HIGH', true, NOW(), 'bootstrap'),
    ('settings.risk_budget.update', 'Update Risk and Budget',
     'Allows editing budget and risk limits used by runtime sizing logic.',
     'SETTINGS', 'HIGH', true, NOW(), 'bootstrap'),
    ('ai.suggestions.view', 'View AI Suggestions',
     'Allows viewing AI suggestion batches and diagnostics outputs.',
     'AI', 'LOW', true, NOW(), 'bootstrap'),
    ('ai.suggestions.accept_reject', 'Accept/Reject AI Suggestions',
     'Allows accepting or rejecting AI suggestion batches.',
     'AI', 'HIGH', true, NOW(), 'bootstrap'),
    ('ai.models.update', 'Update AI Models',
     'Allows changing AI routing allowlists and primary/fallback model chains.',
     'AI', 'HIGH', true, NOW(), 'bootstrap'),
    ('demo.enable_disable', 'Enable/Disable Demo Trading',
     'Allows enabling or disabling demo trading runtime mode.',
     'DEMO', 'HIGH', true, NOW(), 'bootstrap'),
    ('demo.run_once', 'Run Demo Cycle Once',
     'Allows triggering a single demo trading cycle manually.',
     'DEMO', 'MED', true, NOW(), 'bootstrap'),
    ('demo.reset', 'Reset Demo State',
     'Allows clearing demo trading state, runs, and trades.',
     'DEMO', 'HIGH', true, NOW(), 'bootstrap'),
    ('demo.ai.accept_reject', 'Accept/Reject Demo AI Suggestions',
     'Allows accepting or rejecting demo AI suggestion batches.',
     'DEMO', 'HIGH', true, NOW(), 'bootstrap'),
    ('exports.download', 'Download Exports',
     'Allows downloading analytics and journal export payloads.',
     'EXPORTS', 'MED', true, NOW(), 'bootstrap'),
    ('errors.view', 'View Errors',
     'Allows viewing backend error center diagnostics and traces.',
     'ERRORS', 'LOW', true, NOW(), 'bootstrap')
ON CONFLICT (key) DO NOTHING;

INSERT INTO operator_permission (key, title, description, group_name, danger_level, enabled, updated_at, updated_by)
SELECT 'scan.autoscan.toggle',
       title,
       description,
       group_name,
       danger_level,
       enabled,
       COALESCE(updated_at, NOW()),
       COALESCE(updated_by, 'bootstrap')
FROM operator_permission
WHERE key = 'scan.autoscan.enable'
ON CONFLICT (key) DO NOTHING;

INSERT INTO operator_permission (key, title, description, group_name, danger_level, enabled, updated_at, updated_by)
SELECT 'ai.models.update',
       title,
       description,
       group_name,
       danger_level,
       enabled,
       COALESCE(updated_at, NOW()),
       COALESCE(updated_by, 'bootstrap')
FROM operator_permission
WHERE key = 'ai.models.manage'
ON CONFLICT (key) DO NOTHING;
