#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_CONFIG="${ROOT_DIR}/src/main/resources/application.yml"
PORT="${SMOKE_PORT:-18086}"
BASE_URL="http://127.0.0.1:${PORT}"
TRACE_ID="smoke-budget-target-auto-execution-$(date +%s)"
OPERATOR_ID="smoke-operator"
FAILED=0
BACKEND_PID=""
BACKEND_LOG="$(mktemp)"
TEMP_FILES=()

register_temp_file() {
  TEMP_FILES+=("$1")
}

pass() {
  echo "PASS: $1"
}

fail() {
  echo "FAIL: $1"
  FAILED=1
}

new_uuid() {
  uuidgen | tr '[:upper:]' '[:lower:]'
}

yaml_value() {
  local key="$1"
  awk -v target="$key" '
    /^spring:/ { in_spring=1; next }
    in_spring && /^  datasource:/ { in_datasource=1; next }
    in_datasource && $0 !~ /^    / { exit }
    in_datasource && $1 == target ":" {
      sub("^    " target ": ", "", $0)
      print $0
      exit
    }
  ' "$APP_CONFIG"
}

DEFAULT_JDBC_URL="$(yaml_value url)"
DEFAULT_DB_USER="$(yaml_value username)"
DEFAULT_DB_PASSWORD="$(yaml_value password)"

DB_USER="${SMOKE_DB_USER:-$DEFAULT_DB_USER}"
DB_PASSWORD="${SMOKE_DB_PASSWORD:-$DEFAULT_DB_PASSWORD}"
SOURCE_JDBC_URL="${SMOKE_DB_JDBC_URL:-$DEFAULT_JDBC_URL}"
ADMIN_DB="${SMOKE_ADMIN_DB:-postgres}"

if [[ "$SOURCE_JDBC_URL" =~ ^jdbc:postgresql://([^:/]+)(:([0-9]+))?/([^?]+)$ ]]; then
  DB_HOST="${BASH_REMATCH[1]}"
  DB_PORT="${BASH_REMATCH[3]:-5432}"
else
  echo "Unable to parse datasource URL: ${SOURCE_JDBC_URL}" >&2
  exit 1
fi

DB_NAME="${SMOKE_DB_NAME:-trade_bot_smoke_budget_target_${RANDOM}_$$}"
SMOKE_JDBC_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}"

ACTIVE_EXECUTION_SQL="('CREATED','PREFLIGHT_VALIDATING','ENTRY_SUBMITTING','ENTRY_SUBMITTED','ENTRY_FILLED','PROTECTION_SUBMITTING','PROTECTION_ACTIVE','ACTIVE','CLOSING','RECONCILING')"

psql_admin() {
  PGPASSWORD="$DB_PASSWORD" psql -X -v ON_ERROR_STOP=1 -q -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$ADMIN_DB" "$@"
}

psql_smoke() {
  PGPASSWORD="$DB_PASSWORD" psql -X -v ON_ERROR_STOP=1 -q -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" "$@"
}

sql_scalar() {
  psql_smoke -At -c "$1" | tr -d '[:space:]'
}

request_json() {
  local method="$1"
  local path="$2"
  local body_file="$3"
  local payload="${4:-}"

  if [[ -n "$payload" ]]; then
    curl -sS --max-time 20 -X "$method" \
      -H "Content-Type: application/json" \
      -H "X-Trace-Id: ${TRACE_ID}" \
      -H "X-Operator-Id: ${OPERATOR_ID}" \
      -o "$body_file" -w "%{http_code}" \
      "${BASE_URL}${path}" \
      --data "$payload" || true
  else
    curl -sS --max-time 20 -X "$method" \
      -H "X-Trace-Id: ${TRACE_ID}" \
      -H "X-Operator-Id: ${OPERATOR_ID}" \
      -o "$body_file" -w "%{http_code}" \
      "${BASE_URL}${path}" || true
  fi
}

wait_for_backend() {
  local body_file
  body_file="$(mktemp)"
  register_temp_file "$body_file"

  for _ in $(seq 1 120); do
    if [[ -n "$BACKEND_PID" ]] && ! kill -0 "$BACKEND_PID" >/dev/null 2>&1; then
      echo "Backend exited unexpectedly." >&2
      tail -n 200 "$BACKEND_LOG" >&2 || true
      return 1
    fi

    local status
    status="$(request_json GET "/api/v1/budget-target-auto-execution/state" "$body_file")"
    if [[ "$status" == "200" ]]; then
      return 0
    fi
    sleep 1
  done

  echo "Backend did not become ready in time." >&2
  tail -n 200 "$BACKEND_LOG" >&2 || true
  return 1
}

wait_for_active_limit_tick() {
  for _ in $(seq 1 90); do
    local count
    count="$(sql_scalar "SELECT COUNT(*) FROM budget_target_session_event WHERE session_id = '${SESSION_ID}' AND event_type = 'ACTIVE_LIMIT_REACHED';")"
    if [[ "$count" =~ ^[0-9]+$ ]] && (( count > 0 )); then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_for_target_stop() {
  local body_file
  body_file="$(mktemp)"
  register_temp_file "$body_file"

  for _ in $(seq 1 90); do
    local status
    status="$(request_json GET "/api/v1/budget-target-auto-execution/state" "$body_file")"
    if [[ "$status" == "200" ]] && jq -e --arg sessionId "$SESSION_ID" '
      .activeSession == null
      and .latestSession.id == $sessionId
      and .latestSession.status == "STOPPED"
      and .latestSession.stopReason == "TARGET_REACHED"
    ' "$body_file" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done

  echo "Timed out waiting for target stop." >&2
  tail -n 200 "$BACKEND_LOG" >&2 || true
  return 1
}

cleanup() {
  local exit_code=$?

  if [[ -n "$BACKEND_PID" ]]; then
    kill "$BACKEND_PID" >/dev/null 2>&1 || true
    wait "$BACKEND_PID" >/dev/null 2>&1 || true
  fi

  if [[ -n "${DB_NAME:-}" ]]; then
    psql_admin -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${DB_NAME}' AND pid <> pg_backend_pid();" >/dev/null 2>&1 || true
    psql_admin -c "DROP DATABASE IF EXISTS \"${DB_NAME}\";" >/dev/null 2>&1 || true
  fi

  for temp_file in "${TEMP_FILES[@]:-}"; do
    rm -f "$temp_file"
  done
  rm -f "$BACKEND_LOG"

  exit "$exit_code"
}
trap cleanup EXIT

echo "Creating disposable smoke database ${DB_NAME}..."
psql_admin -c "CREATE DATABASE \"${DB_NAME}\";"

./gradlew --no-daemon bootJar >/dev/null
APP_JAR="$(find "${ROOT_DIR}/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1 || true)"

if [[ -z "$APP_JAR" ]]; then
  echo "Unable to locate application jar under build/libs." >&2
  exit 1
fi

echo "Starting backend on ${BASE_URL} using profile smoke..."
(
  cd "$ROOT_DIR"
  SPRING_PROFILES_ACTIVE=smoke \
  SERVER_PORT="$PORT" \
  SPRING_DATASOURCE_URL="$SMOKE_JDBC_URL" \
  SPRING_DATASOURCE_USERNAME="$DB_USER" \
  SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD" \
  SPRING_FLYWAY_URL="$SMOKE_JDBC_URL" \
  SPRING_FLYWAY_USER="$DB_USER" \
  SPRING_FLYWAY_PASSWORD="$DB_PASSWORD" \
  java -jar "$APP_JAR"
) >"$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!

wait_for_backend

setup_body="$(mktemp)"
register_temp_file "$setup_body"
setup_payload="$(cat <<'JSON'
{
  "patch": {
    "permissions": {
      "live.execution.enabled": true,
      "live.execution.auto_session.manage": true,
      "live.execution.auto_session.audit.view": true
    },
    "liveExecution": {
      "readOnly": false
    },
    "scan": {
      "safeMode": false
    },
    "budgetTargetAutoExecution": {
      "enabled": true,
      "readOnly": false,
      "defaultBudgetUsdt": 150,
      "defaultTargetProfitUsdt": 25,
      "maxConcurrentPositions": 3,
      "allowNewSessionStart": true,
      "allowCloseAllOnTarget": true,
      "killSwitch": false,
      "requireBinanceHealthPass": false,
      "requireOperatorConfirmationForStop": false,
      "sessionTimeoutMinutes": 240
    }
  },
  "reason": "smoke-budget-target-setup"
}
JSON
)"
setup_status="$(request_json POST "/api/v1/control-center/state" "$setup_body" "$setup_payload")"
if [[ "$setup_status" != "200" ]]; then
  echo "Control Center setup failed (${setup_status})." >&2
  cat "$setup_body" >&2 || true
  exit 1
fi

start_body="$(mktemp)"
register_temp_file "$start_body"
start_payload="$(cat <<'JSON'
{
  "command": "TURN_ON",
  "budgetAmountUsdt": 150,
  "targetProfitUsdt": 25,
  "reason": "smoke-budget-target-start"
}
JSON
)"
start_status="$(request_json POST "/api/v1/budget-target-auto-execution/state" "$start_body" "$start_payload")"
if [[ "$start_status" != "200" ]]; then
  echo "Session start failed (${start_status})." >&2
  cat "$start_body" >&2 || true
  fail "session start succeeded"
  exit 1
fi

SESSION_ID="$(jq -r '.activeSession.id // .latestSession.id // empty' "$start_body")"
if [[ -n "$SESSION_ID" ]]; then
  pass "session start succeeded"
else
  fail "session start succeeded"
  exit 1
fi

if jq -e '.activeSession.status == "RUNNING"' "$start_body" >/dev/null 2>&1; then
  pass "RUNNING state appears"
else
  fail "RUNNING state appears"
  exit 1
fi

SCAN_RUN_ID="$(new_uuid)"
REC_ACTIVE_1="$(new_uuid)"
REC_ACTIVE_2="$(new_uuid)"
REC_ACTIVE_3="$(new_uuid)"
REC_DONE_1="$(new_uuid)"
REC_DONE_2="$(new_uuid)"
REC_DONE_3="$(new_uuid)"
REC_DONE_4="$(new_uuid)"
EXEC_ACTIVE_1="$(new_uuid)"
EXEC_ACTIVE_2="$(new_uuid)"
EXEC_ACTIVE_3="$(new_uuid)"
EXEC_DONE_1="$(new_uuid)"
EXEC_DONE_2="$(new_uuid)"
EXEC_DONE_3="$(new_uuid)"
EXEC_DONE_4="$(new_uuid)"
SYNC_ACTIVE_1="$(new_uuid)"
SYNC_ACTIVE_2="$(new_uuid)"
SYNC_ACTIVE_3="$(new_uuid)"
SYNC_DONE_1="$(new_uuid)"
SYNC_DONE_2="$(new_uuid)"
SYNC_DONE_3="$(new_uuid)"
SYNC_DONE_4="$(new_uuid)"
LEDGER_TOP_UP_ID="$(new_uuid)"

psql_smoke <<SQL
INSERT INTO scan_run (
    id, started_at, finished_at, interval_minutes, topn, status, trigger_type, requested_at, correlation_id, dedup_key
) VALUES (
    '${SCAN_RUN_ID}',
    NOW() - INTERVAL '15 minutes',
    NOW() - INTERVAL '14 minutes',
    20,
    20,
    'FINISHED',
    'AUTO_SESSION',
    NOW() - INTERVAL '15 minutes',
    '${TRACE_ID}',
    '${TRACE_ID}-seed'
);

INSERT INTO recommendation (id, scan_run_id, symbol, side, rationale_text, confidence_score, created_at, status) VALUES
    ('${REC_ACTIVE_1}', '${SCAN_RUN_ID}', 'BTCUSDT', 'BUY', 'Smoke active BTC', 0.91, NOW() - INTERVAL '14 minutes', 'ACTIVE'),
    ('${REC_ACTIVE_2}', '${SCAN_RUN_ID}', 'ETHUSDT', 'BUY', 'Smoke active ETH', 0.89, NOW() - INTERVAL '13 minutes', 'ACTIVE'),
    ('${REC_ACTIVE_3}', '${SCAN_RUN_ID}', 'BNBUSDT', 'BUY', 'Smoke active BNB', 0.88, NOW() - INTERVAL '12 minutes', 'ACTIVE'),
    ('${REC_DONE_1}', '${SCAN_RUN_ID}', 'SOLUSDT', 'BUY', 'Smoke completed SOL win', 0.87, NOW() - INTERVAL '11 minutes', 'COMPLETED'),
    ('${REC_DONE_2}', '${SCAN_RUN_ID}', 'BTCUSDT', 'BUY', 'Smoke completed BTC win', 0.86, NOW() - INTERVAL '10 minutes', 'COMPLETED'),
    ('${REC_DONE_3}', '${SCAN_RUN_ID}', 'ETHUSDT', 'BUY', 'Smoke completed ETH win', 0.85, NOW() - INTERVAL '9 minutes', 'COMPLETED'),
    ('${REC_DONE_4}', '${SCAN_RUN_ID}', 'SOLUSDT', 'BUY', 'Smoke completed SOL loss', 0.84, NOW() - INTERVAL '8 minutes', 'COMPLETED');

INSERT INTO live_trade_execution (
    id,
    recommendation_id,
    session_id,
    trigger_mode,
    symbol,
    side,
    operator_id,
    trace_id,
    client_request_id,
    dry_run,
    payload_snapshot_json,
    preflight_json,
    exchange_response_json,
    entry_response_json,
    protection_response_json,
    execution_status,
    error_code,
    error_message,
    requires_intervention,
    reserved_margin_usdt,
    requested_budget_slice_usdt,
    requested_qty,
    actual_filled_qty,
    realized_gross_pnl_usdt,
    realized_fees_usdt,
    realized_net_pnl_usdt,
    close_reason,
    entry_client_order_id,
    sl_client_order_id,
    tp_client_order_id,
    emergency_close_client_order_id,
    entry_order_id,
    sl_order_id,
    tp_order_id,
    emergency_close_order_id,
    position_slot,
    reconcile_count,
    submitted_at,
    completed_at,
    last_reconciled_at,
    created_at,
    updated_at
) VALUES
    ('${EXEC_ACTIVE_1}', '${REC_ACTIVE_1}', '${SESSION_ID}', 'AUTO_SESSION', 'BTCUSDT', 'BUY', 'system', '${TRACE_ID}', '11111111-1111-1111-1111-111111111111', FALSE, '{}'::jsonb, '{}'::jsonb, '{"position":{"unRealizedProfit":"0"}}'::jsonb, '{}'::jsonb, '{}'::jsonb, 'ACTIVE', NULL, NULL, FALSE, 15, 20, 0.01000000, 0.01000000, 0, 0, 0, NULL, 'smoke-btc-entry', 'smoke-btc-sl', 'smoke-btc-tp', NULL, 1001, 2001, 3001, NULL, 1, 0, NOW() - INTERVAL '7 minutes', NULL, NOW(), NOW() - INTERVAL '7 minutes', NOW()),
    ('${EXEC_ACTIVE_2}', '${REC_ACTIVE_2}', '${SESSION_ID}', 'AUTO_SESSION', 'ETHUSDT', 'BUY', 'system', '${TRACE_ID}', '22222222-2222-2222-2222-222222222222', FALSE, '{}'::jsonb, '{}'::jsonb, '{"position":{"unRealizedProfit":"0"}}'::jsonb, '{}'::jsonb, '{}'::jsonb, 'ACTIVE', NULL, NULL, FALSE, 15, 20, 0.05000000, 0.05000000, 0, 0, 0, NULL, 'smoke-eth-entry', 'smoke-eth-sl', 'smoke-eth-tp', NULL, 1002, 2002, 3002, NULL, 2, 0, NOW() - INTERVAL '6 minutes', NULL, NOW(), NOW() - INTERVAL '6 minutes', NOW()),
    ('${EXEC_ACTIVE_3}', '${REC_ACTIVE_3}', '${SESSION_ID}', 'AUTO_SESSION', 'BNBUSDT', 'BUY', 'system', '${TRACE_ID}', '33333333-3333-3333-3333-333333333333', FALSE, '{}'::jsonb, '{}'::jsonb, '{"position":{"unRealizedProfit":"0"}}'::jsonb, '{}'::jsonb, '{}'::jsonb, 'ACTIVE', NULL, NULL, FALSE, 15, 20, 0.20000000, 0.20000000, 0, 0, 0, NULL, 'smoke-bnb-entry', 'smoke-bnb-sl', 'smoke-bnb-tp', NULL, 1003, 2003, 3003, NULL, 3, 0, NOW() - INTERVAL '5 minutes', NULL, NOW(), NOW() - INTERVAL '5 minutes', NOW()),
    ('${EXEC_DONE_1}', '${REC_DONE_1}', '${SESSION_ID}', 'AUTO_SESSION', 'SOLUSDT', 'BUY', 'system', '${TRACE_ID}', '44444444-4444-4444-4444-444444444444', FALSE, '{}'::jsonb, '{}'::jsonb, '{"position":{"unRealizedProfit":"0"}}'::jsonb, '{}'::jsonb, '{}'::jsonb, 'CLOSED', NULL, NULL, FALSE, 12, 18, 0.30000000, 0.30000000, 10, 2, 8, 'TAKE_PROFIT', 'smoke-sol-entry-1', 'smoke-sol-sl-1', 'smoke-sol-tp-1', NULL, 1004, 2004, 3004, NULL, 1, 1, NOW() - INTERVAL '11 minutes', NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '11 minutes', NOW() - INTERVAL '10 minutes'),
    ('${EXEC_DONE_2}', '${REC_DONE_2}', '${SESSION_ID}', 'AUTO_SESSION', 'BTCUSDT', 'BUY', 'system', '${TRACE_ID}', '55555555-5555-5555-5555-555555555555', FALSE, '{}'::jsonb, '{}'::jsonb, '{"position":{"unRealizedProfit":"0"}}'::jsonb, '{}'::jsonb, '{}'::jsonb, 'CLOSED', NULL, NULL, FALSE, 12, 18, 0.01000000, 0.01000000, 6, 1, 5, 'TAKE_PROFIT', 'smoke-btc-entry-2', 'smoke-btc-sl-2', 'smoke-btc-tp-2', NULL, 1005, 2005, 3005, NULL, 2, 1, NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '9 minutes', NOW() - INTERVAL '9 minutes', NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '9 minutes'),
    ('${EXEC_DONE_3}', '${REC_DONE_3}', '${SESSION_ID}', 'AUTO_SESSION', 'ETHUSDT', 'BUY', 'system', '${TRACE_ID}', '66666666-6666-6666-6666-666666666666', FALSE, '{}'::jsonb, '{}'::jsonb, '{"position":{"unRealizedProfit":"0"}}'::jsonb, '{}'::jsonb, '{}'::jsonb, 'CLOSED', NULL, NULL, FALSE, 12, 18, 0.05000000, 0.05000000, 10, 1, 9, 'TAKE_PROFIT', 'smoke-eth-entry-2', 'smoke-eth-sl-2', 'smoke-eth-tp-2', NULL, 1006, 2006, 3006, NULL, 3, 1, NOW() - INTERVAL '9 minutes', NOW() - INTERVAL '8 minutes', NOW() - INTERVAL '8 minutes', NOW() - INTERVAL '9 minutes', NOW() - INTERVAL '8 minutes'),
    ('${EXEC_DONE_4}', '${REC_DONE_4}', '${SESSION_ID}', 'AUTO_SESSION', 'SOLUSDT', 'BUY', 'system', '${TRACE_ID}', '77777777-7777-7777-7777-777777777777', FALSE, '{}'::jsonb, '{}'::jsonb, '{"position":{"unRealizedProfit":"0"}}'::jsonb, '{}'::jsonb, '{}'::jsonb, 'CLOSED', NULL, NULL, FALSE, 12, 18, 0.30000000, 0.30000000, -1.5, 0.5, -2, 'STOP_LOSS', 'smoke-sol-entry-2', 'smoke-sol-sl-2', 'smoke-sol-tp-2', NULL, 1007, 2007, 3007, NULL, 1, 1, NOW() - INTERVAL '8 minutes', NOW() - INTERVAL '7 minutes', NOW() - INTERVAL '7 minutes', NOW() - INTERVAL '8 minutes', NOW() - INTERVAL '7 minutes');

INSERT INTO live_trade_pnl_ledger (
    session_id, execution_id, event_type, amount_usdt, event_ts, source_type, source_ref, before_json, after_json, notes, trace_id
) VALUES
    ('${SESSION_ID}', '${EXEC_DONE_1}', 'REALIZED_NET_PNL', 8, NOW() - INTERVAL '10 minutes', 'SMOKE_COMPLETED', 'smoke-net-1', '{}'::jsonb, '{}'::jsonb, 'Smoke completed trade 1', '${TRACE_ID}'),
    ('${SESSION_ID}', '${EXEC_DONE_2}', 'REALIZED_NET_PNL', 5, NOW() - INTERVAL '9 minutes', 'SMOKE_COMPLETED', 'smoke-net-2', '{}'::jsonb, '{}'::jsonb, 'Smoke completed trade 2', '${TRACE_ID}'),
    ('${SESSION_ID}', '${EXEC_DONE_3}', 'REALIZED_NET_PNL', 9, NOW() - INTERVAL '8 minutes', 'SMOKE_COMPLETED', 'smoke-net-3', '{}'::jsonb, '{}'::jsonb, 'Smoke completed trade 3', '${TRACE_ID}'),
    ('${SESSION_ID}', '${EXEC_DONE_4}', 'REALIZED_NET_PNL', -2, NOW() - INTERVAL '7 minutes', 'SMOKE_COMPLETED', 'smoke-net-4', '{}'::jsonb, '{}'::jsonb, 'Smoke completed trade 4', '${TRACE_ID}');

INSERT INTO exchange_sync_snapshot (
    id, session_id, execution_id, symbol, sync_type, sync_status, trace_id,
    divergence_detected, requires_intervention, open_position, active_open_order_count, active_protection_order_count,
    stop_loss_active, take_profit_active, emergency_close_working, emergency_close_filled, protection_triggered,
    entry_order_status, stop_loss_status, take_profit_status,
    position_quantity, actual_filled_qty, avg_fill_price, entry_price, mark_price,
    realized_gross_pnl_usdt, realized_fees_usdt, realized_net_pnl_usdt, unrealized_pnl_usdt,
    last_successful_sync_at, sync_completed_at, snapshot_json, created_at
) VALUES
    ('${SYNC_ACTIVE_1}', '${SESSION_ID}', '${EXEC_ACTIVE_1}', 'BTCUSDT', 'SCHEDULED', 'SUCCESS', '${TRACE_ID}', FALSE, FALSE, TRUE, 2, 2, TRUE, TRUE, FALSE, FALSE, FALSE, 'FILLED', 'NEW', 'NEW', 0.01000000, 0.01000000, 100000, 100000, 100050, 0, 0, 0, 0.20, NOW(), NOW(), '{"symbol":"BTCUSDT","status":"HEALTHY"}'::jsonb, NOW()),
    ('${SYNC_ACTIVE_2}', '${SESSION_ID}', '${EXEC_ACTIVE_2}', 'ETHUSDT', 'SCHEDULED', 'SUCCESS', '${TRACE_ID}', FALSE, FALSE, TRUE, 2, 2, TRUE, TRUE, FALSE, FALSE, FALSE, 'FILLED', 'NEW', 'NEW', 0.05000000, 0.05000000, 2500, 2500, 2505, 0, 0, 0, 0.10, NOW(), NOW(), '{"symbol":"ETHUSDT","status":"HEALTHY"}'::jsonb, NOW()),
    ('${SYNC_ACTIVE_3}', '${SESSION_ID}', '${EXEC_ACTIVE_3}', 'BNBUSDT', 'SCHEDULED', 'SUCCESS', '${TRACE_ID}', FALSE, FALSE, TRUE, 2, 2, TRUE, TRUE, FALSE, FALSE, FALSE, 'FILLED', 'NEW', 'NEW', 0.20000000, 0.20000000, 600, 600, 602, 0, 0, 0, 0.05, NOW(), NOW(), '{"symbol":"BNBUSDT","status":"HEALTHY"}'::jsonb, NOW()),
    ('${SYNC_DONE_1}', '${SESSION_ID}', '${EXEC_DONE_1}', 'SOLUSDT', 'SCHEDULED', 'SUCCESS', '${TRACE_ID}', FALSE, FALSE, FALSE, 0, 0, FALSE, FALSE, FALSE, FALSE, FALSE, 'FILLED', NULL, NULL, 0, 0.30000000, 100200, 100200, 100200, 10, 2, 8, 0, NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '10 minutes', '{"symbol":"SOLUSDT","closeReason":"TAKE_PROFIT"}'::jsonb, NOW() - INTERVAL '10 minutes'),
    ('${SYNC_DONE_2}', '${SESSION_ID}', '${EXEC_DONE_2}', 'BTCUSDT', 'SCHEDULED', 'SUCCESS', '${TRACE_ID}', FALSE, FALSE, FALSE, 0, 0, FALSE, FALSE, FALSE, FALSE, FALSE, 'FILLED', NULL, NULL, 0, 0.01000000, 100100, 100100, 100100, 6, 1, 5, 0, NOW() - INTERVAL '9 minutes', NOW() - INTERVAL '9 minutes', '{"symbol":"BTCUSDT","closeReason":"TAKE_PROFIT"}'::jsonb, NOW() - INTERVAL '9 minutes'),
    ('${SYNC_DONE_3}', '${SESSION_ID}', '${EXEC_DONE_3}', 'ETHUSDT', 'SCHEDULED', 'SUCCESS', '${TRACE_ID}', FALSE, FALSE, FALSE, 0, 0, FALSE, FALSE, FALSE, FALSE, FALSE, 'FILLED', NULL, NULL, 0, 0.05000000, 2502, 2502, 2502, 10, 1, 9, 0, NOW() - INTERVAL '8 minutes', NOW() - INTERVAL '8 minutes', '{"symbol":"ETHUSDT","closeReason":"TAKE_PROFIT"}'::jsonb, NOW() - INTERVAL '8 minutes'),
    ('${SYNC_DONE_4}', '${SESSION_ID}', '${EXEC_DONE_4}', 'SOLUSDT', 'SCHEDULED', 'SUCCESS', '${TRACE_ID}', FALSE, FALSE, FALSE, 0, 0, FALSE, FALSE, FALSE, FALSE, FALSE, 'FILLED', NULL, NULL, 0, 0.30000000, 100050, 100050, 100050, -1.5, 0.5, -2, 0, NOW() - INTERVAL '7 minutes', NOW() - INTERVAL '7 minutes', '{"symbol":"SOLUSDT","closeReason":"STOP_LOSS"}'::jsonb, NOW() - INTERVAL '7 minutes');
SQL

if wait_for_active_limit_tick; then
  state_running_body="$(mktemp)"
  register_temp_file "$state_running_body"
  trades_running_body="$(mktemp)"
  register_temp_file "$trades_running_body"
  session_running_body="$(mktemp)"
  register_temp_file "$session_running_body"
  trade_detail_running_body="$(mktemp)"
  register_temp_file "$trade_detail_running_body"

  request_json GET "/api/v1/budget-target-auto-execution/state" "$state_running_body" >/dev/null
  request_json GET "/api/v1/budget-target-auto-execution/sessions/${SESSION_ID}/trades?limit=20" "$trades_running_body" >/dev/null
  request_json GET "/api/v1/budget-target-auto-execution/sessions/${SESSION_ID}" "$session_running_body" >/dev/null
  request_json GET "/api/v1/budget-target-auto-execution/sessions/${SESSION_ID}/trades/${EXEC_ACTIVE_1}" "$trade_detail_running_body" >/dev/null

  db_active_mid="$(sql_scalar "SELECT COUNT(*) FROM live_trade_execution WHERE session_id = '${SESSION_ID}' AND execution_status IN ${ACTIVE_EXECUTION_SQL};")"
  db_total_mid="$(sql_scalar "SELECT COUNT(*) FROM live_trade_execution WHERE session_id = '${SESSION_ID}';")"
  db_completed_mid="$(sql_scalar "SELECT COUNT(*) FROM live_trade_execution WHERE session_id = '${SESSION_ID}' AND execution_status NOT IN ${ACTIVE_EXECUTION_SQL};")"

  rest_active_mid="$(jq -r '.activeCount' "$trades_running_body")"
  rest_total_mid="$(jq -r '.total' "$trades_running_body")"
  rest_completed_mid="$(jq -r '.completedCount' "$trades_running_body")"
  session_status_mid="$(jq -r '.summary.status' "$session_running_body")"

  if [[ "$session_status_mid" == "RUNNING" && "$db_active_mid" == "3" && "$rest_active_mid" == "3" && "$db_total_mid" == "$rest_total_mid" ]]; then
    pass "at most 3 active positions allowed"
  else
    fail "at most 3 active positions allowed"
  fi

  if jq -e '
      .syncHealth.status == "HEALTHY"
      and .syncHealth.openPositionCount == 3
      and .syncHealth.activeOpenOrderCount == 6
    ' "$state_running_body" >/dev/null 2>&1 \
    && jq -e '
      .syncHealth.status == "HEALTHY"
      and .summary.syncHealth.status == "HEALTHY"
    ' "$session_running_body" >/dev/null 2>&1 \
    && jq -e '
      (.syncSnapshots | length) >= 1
      and .execution.syncHealth.status == "HEALTHY"
    ' "$trade_detail_running_body" >/dev/null 2>&1; then
    pass "session sync health and trade snapshots are exposed truthfully"
  else
    fail "session sync health and trade snapshots are exposed truthfully"
  fi

  if [[ "$db_completed_mid" == "$rest_completed_mid" ]] && (( db_completed_mid > 3 )); then
    pass "completed lifetime trades exceed 3"
  else
    fail "completed lifetime trades exceed 3"
  fi
else
  fail "at most 3 active positions allowed"
  fail "completed lifetime trades exceed 3"
  exit 1
fi

psql_smoke <<SQL
INSERT INTO live_trade_pnl_ledger (
    id, session_id, execution_id, event_type, amount_usdt, event_ts, source_type, source_ref, before_json, after_json, notes, trace_id
) VALUES (
    '${LEDGER_TOP_UP_ID}',
    '${SESSION_ID}',
    NULL,
    'REALIZED_NET_PNL',
    6,
    NOW(),
    'SMOKE_ADJUSTMENT',
    'smoke-target-top-up',
    '{}'::jsonb,
    '{}'::jsonb,
    'Smoke target top-up to trigger TARGET_REACHED.',
    '${TRACE_ID}'
);
SQL

if wait_for_target_stop; then
  pass "session stops when realized target is reached"
else
  fail "session stops when realized target is reached"
  exit 1
fi

state_final_body="$(mktemp)"
register_temp_file "$state_final_body"
trades_final_body="$(mktemp)"
register_temp_file "$trades_final_body"
timeline_final_body="$(mktemp)"
register_temp_file "$timeline_final_body"
session_final_body="$(mktemp)"
register_temp_file "$session_final_body"

request_json GET "/api/v1/budget-target-auto-execution/state" "$state_final_body" >/dev/null
request_json GET "/api/v1/budget-target-auto-execution/sessions/${SESSION_ID}" "$session_final_body" >/dev/null
request_json GET "/api/v1/budget-target-auto-execution/sessions/${SESSION_ID}/trades?limit=20" "$trades_final_body" >/dev/null
request_json GET "/api/v1/budget-target-auto-execution/sessions/${SESSION_ID}/timeline?limit=100" "$timeline_final_body" >/dev/null

db_active_final="$(sql_scalar "SELECT COUNT(*) FROM live_trade_execution WHERE session_id = '${SESSION_ID}' AND execution_status IN ${ACTIVE_EXECUTION_SQL};")"
db_total_final="$(sql_scalar "SELECT COUNT(*) FROM live_trade_execution WHERE session_id = '${SESSION_ID}';")"
db_completed_final="$(sql_scalar "SELECT COUNT(*) FROM live_trade_execution WHERE session_id = '${SESSION_ID}' AND execution_status NOT IN ${ACTIVE_EXECUTION_SQL};")"
db_close_all_events="$(sql_scalar "SELECT COUNT(*) FROM budget_target_session_event WHERE session_id = '${SESSION_ID}' AND event_type = 'CLOSE_ALL_ATTEMPT';")"
timeline_close_all_events="$(jq '[.items[] | select(.eventType == "CLOSE_ALL_ATTEMPT")] | length' "$timeline_final_body")"

if (( db_close_all_events > 0 )) && [[ "$db_close_all_events" == "$timeline_close_all_events" ]]; then
  pass "close-all path writes audit events"
else
  fail "close-all path writes audit events"
fi

rest_total_final="$(jq -r '.total' "$trades_final_body")"
rest_active_final="$(jq -r '.activeCount' "$trades_final_body")"
rest_completed_final="$(jq -r '.completedCount' "$trades_final_body")"
detail_trade_count="$(jq -r '.tradeCount' "$session_final_body")"
state_order_count="$(jq '.orders | length' "$state_final_body")"
latest_status="$(jq -r '.latestSession.status' "$state_final_body")"
latest_stop_reason="$(jq -r '.latestSession.stopReason' "$state_final_body")"
latest_realized="$(jq -r '.latestSession.realizedNetPnlUsdt' "$state_final_body")"

if [[ "$db_total_final" == "$rest_total_final" ]] \
  && [[ "$db_active_final" == "$rest_active_final" ]] \
  && [[ "$db_completed_final" == "$rest_completed_final" ]] \
  && [[ "$db_total_final" == "$detail_trade_count" ]] \
  && [[ "$db_total_final" == "$state_order_count" ]] \
  && [[ "$latest_status" == "STOPPED" ]] \
  && [[ "$latest_stop_reason" == "TARGET_REACHED" ]] \
  && awk "BEGIN { exit !(${latest_realized} >= 25) }"; then
  pass "DB counts and REST payloads match"
else
  fail "DB counts and REST payloads match"
fi

if [[ "$FAILED" -ne 0 ]]; then
  echo "Budget target auto-execution smoke check failed."
  tail -n 200 "$BACKEND_LOG" >&2 || true
  exit 1
fi

echo "Budget target auto-execution smoke check passed."
