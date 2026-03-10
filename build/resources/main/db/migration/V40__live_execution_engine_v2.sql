ALTER TABLE live_trade_execution
    ADD COLUMN IF NOT EXISTS error_details_json JSONB,
    ADD COLUMN IF NOT EXISTS requires_intervention BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS critical_issue_json JSONB;

UPDATE live_trade_execution
SET execution_status = CASE execution_status
    WHEN 'REQUESTED' THEN 'CREATED'
    WHEN 'BLOCKED' THEN 'PREFLIGHT_REJECTED'
    WHEN 'DRY_RUN' THEN 'PREFLIGHT_REJECTED'
    WHEN 'SUBMITTING' THEN 'ENTRY_SUBMITTING'
    WHEN 'ENTRY_PARTIALLY_FILLED' THEN 'ENTRY_FILLED'
    WHEN 'PROTECTION_SUBMITTED' THEN 'PROTECTION_SUBMITTING'
    WHEN 'OPEN' THEN 'ACTIVE'
    WHEN 'PENDING_RECONCILE' THEN 'RECONCILING'
    WHEN 'RECONCILED' THEN 'CLOSED'
    WHEN 'EMERGENCY_CLOSE_SUBMITTED' THEN 'CLOSING'
    WHEN 'EMERGENCY_CLOSE_FILLED' THEN 'CLOSED'
    WHEN 'PROTECTION_FAILED' THEN 'RECONCILING'
    WHEN 'EMERGENCY_CLOSE_FAILED' THEN 'RECONCILING'
    ELSE execution_status
END;

UPDATE live_trade_execution
SET requires_intervention = TRUE,
    critical_issue_json = jsonb_build_object(
        'code', COALESCE(error_code, 'LEGACY_PARTIAL_FAILURE'),
        'message', COALESCE(error_message, 'Legacy live execution requires reconciliation.'),
        'details', jsonb_build_object('legacyState', 'PROTECTION_FAILED'),
        'raisedAt', COALESCE(updated_at, created_at, NOW())
    )
WHERE critical_issue_json IS NULL
  AND (
        error_code IS NOT NULL
        OR error_message IS NOT NULL
      )
  AND execution_status = 'RECONCILING'
  AND (
        sl_order_id IS NULL
        OR tp_order_id IS NULL
        OR emergency_close_order_id IS NOT NULL
      );

DROP INDEX IF EXISTS uq_live_trade_execution_active_position_slot;

CREATE UNIQUE INDEX IF NOT EXISTS uq_live_trade_execution_active_position_slot
    ON live_trade_execution (session_id, position_slot)
    WHERE session_id IS NOT NULL
      AND position_slot IS NOT NULL
      AND execution_status IN (
            'CREATED',
            'PREFLIGHT_VALIDATING',
            'ENTRY_SUBMITTING',
            'ENTRY_SUBMITTED',
            'ENTRY_FILLED',
            'PROTECTION_SUBMITTING',
            'PROTECTION_ACTIVE',
            'ACTIVE',
            'CLOSING',
            'RECONCILING'
      );
