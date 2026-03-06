CREATE TABLE IF NOT EXISTS control_center_state (
    id INT PRIMARY KEY CHECK (id = 1),
    config_json JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(128),
    version INT NOT NULL DEFAULT 1
);
