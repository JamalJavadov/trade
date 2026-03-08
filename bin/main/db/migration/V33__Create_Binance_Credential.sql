CREATE TABLE binance_credential (
    id SERIAL PRIMARY KEY,
    api_key VARCHAR(255) NOT NULL,
    public_key_pem TEXT,
    private_key_encrypted TEXT,
    auth_mode VARCHAR(50) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
