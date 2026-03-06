CREATE TABLE IF NOT EXISTS operator_permission (
    key VARCHAR(128) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    title VARCHAR(255) NOT NULL,
    description VARCHAR(500) NOT NULL,
    group_name VARCHAR(32) NOT NULL,
    danger_level VARCHAR(16) NOT NULL,
    updated_at TIMESTAMP,
    updated_by VARCHAR(128)
);
