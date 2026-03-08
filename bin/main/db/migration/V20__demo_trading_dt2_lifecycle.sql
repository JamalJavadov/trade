ALTER TABLE demo_run
    DROP COLUMN IF EXISTS scan_run_id;

ALTER TABLE demo_account
    ADD COLUMN IF NOT EXISTS mode_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE demo_trade
    ADD COLUMN IF NOT EXISTS remaining_qty NUMERIC(30, 8) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS realized_pnl_usdt NUMERIC(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS current_sl_price NUMERIC(30, 8),
    ADD COLUMN IF NOT EXISTS stage INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_mark_price NUMERIC(30, 8),
    ADD COLUMN IF NOT EXISTS risk_usdt_initial NUMERIC(20, 8),
    ADD COLUMN IF NOT EXISTS entry_fee_usdt NUMERIC(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS exit_fee_usdt NUMERIC(20, 8) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_fees_usdt NUMERIC(20, 8) NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_demo_trade_stage'
    ) THEN
        ALTER TABLE demo_trade
            ADD CONSTRAINT chk_demo_trade_stage CHECK (stage IN (0, 1, 2));
    END IF;
END $$;

UPDATE demo_trade
SET remaining_qty = qty,
    current_sl_price = sl_price,
    stage = 0
WHERE status = 'OPEN';

UPDATE demo_trade
SET remaining_qty = COALESCE(remaining_qty, 0),
    realized_pnl_usdt = COALESCE(realized_pnl_usdt, 0),
    stage = COALESCE(stage, 0),
    entry_fee_usdt = COALESCE(entry_fee_usdt, 0),
    exit_fee_usdt = COALESCE(exit_fee_usdt, 0),
    total_fees_usdt = COALESCE(total_fees_usdt, 0)
WHERE status <> 'OPEN';
