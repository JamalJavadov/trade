CREATE TABLE app_settings (
    id VARCHAR(50) PRIMARY KEY,
    safe_mode BOOLEAN NOT NULL DEFAULT FALSE,
    scheduler_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    scan_interval_minutes INT NOT NULL DEFAULT 20
);

INSERT INTO app_settings (id, safe_mode, scheduler_enabled, scan_interval_minutes) 
VALUES ('DEFAULT', false, true, 20) ON CONFLICT DO NOTHING;
