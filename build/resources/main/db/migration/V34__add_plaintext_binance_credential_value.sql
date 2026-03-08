ALTER TABLE binance_credential
    ADD COLUMN IF NOT EXISTS private_key_value TEXT;
