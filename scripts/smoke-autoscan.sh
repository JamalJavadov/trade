#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
TRACE_ID="smoke-autoscan-$(date +%s)"
failed=0

require_structured_error() {
  local body_file="$1"
  if ! jq -e '
      (.path | type == "string")
      and (.errorCode | type == "string")
      and (.message | type == "string")
      and ((.traceId | type) == "string" or .traceId == null)
    ' "$body_file" >/dev/null 2>&1; then
    echo "  ! Expected structured API error payload."
    failed=1
  fi
}

request_json() {
  local method="$1"
  local path="$2"
  local body_file="$3"
  local payload="${4:-}"

  if [[ -n "$payload" ]]; then
    curl -sS --max-time 15 -X "$method" \
      -H "Content-Type: application/json" \
      -H "X-Trace-Id: ${TRACE_ID}" \
      -o "$body_file" -w "%{http_code}" \
      "${BASE_URL}${path}" \
      --data "$payload" || true
  else
    curl -sS --max-time 15 -X "$method" \
      -H "X-Trace-Id: ${TRACE_ID}" \
      -o "$body_file" -w "%{http_code}" \
      "${BASE_URL}${path}" || true
  fi
}

state_body="$(mktemp)"
state_status="$(request_json GET "/api/v1/scans/autoscan/state" "$state_body")"
echo "GET /api/v1/scans/autoscan/state -> ${state_status}"

if [[ "$state_status" == "500" || "$state_status" == "000" ]]; then
  echo "  ! Autoscan state endpoint failed."
  failed=1
elif [[ "$state_status" -ge 400 ]]; then
  require_structured_error "$state_body"
fi

enable_payload='{"patch":{"scan":{"autoscanEnabled":true,"safeMode":false,"intervalMinutes":1}},"reason":"smoke-autoscan-enable"}'
enable_body="$(mktemp)"
enable_status="$(request_json POST "/api/v1/control-center/state" "$enable_body" "$enable_payload")"
echo "POST /api/v1/control-center/state (enable autoscan) -> ${enable_status}"

if [[ "$enable_status" == "500" || "$enable_status" == "000" ]]; then
  echo "  ! Autoscan enable call failed."
  failed=1
elif [[ "$enable_status" -ge 400 ]]; then
  require_structured_error "$enable_body"
else
  if ! jq -e '.config.scan.autoscanEnabled == true and (.config.scan.intervalMinutes | tonumber) >= 1' "$enable_body" >/dev/null 2>&1; then
    echo "  ! Enable response does not confirm autoscan enabled."
    failed=1
  fi
fi

state2_body="$(mktemp)"
state2_status="$(request_json GET "/api/v1/scans/autoscan/state" "$state2_body")"
echo "GET /api/v1/scans/autoscan/state (after enable) -> ${state2_status}"

if [[ "$state2_status" == "500" || "$state2_status" == "000" ]]; then
  echo "  ! Autoscan state failed after enable."
  failed=1
elif [[ "$state2_status" -ge 400 ]]; then
  require_structured_error "$state2_body"
else
  if ! jq -e '.autoscanEnabled == true and (.nextRunAt == null or (.nextRunAt | type == "string"))' "$state2_body" >/dev/null 2>&1; then
    echo "  ! Autoscan state shape invalid after enable."
    failed=1
  fi
fi

run_body="$(mktemp)"
run_status="$(request_json POST "/api/v1/scans/run-once" "$run_body" "{}")"
echo "POST /api/v1/scans/run-once -> ${run_status}"

scan_run_id=""
if [[ "$run_status" == "500" || "$run_status" == "000" ]]; then
  echo "  ! Run-now endpoint failed."
  failed=1
elif [[ "$run_status" -ge 400 ]]; then
  require_structured_error "$run_body"
else
  scan_run_id="$(jq -r '.scanRunId // empty' "$run_body" 2>/dev/null || true)"
  run_status_value="$(jq -r '.status // empty' "$run_body" 2>/dev/null || true)"
  echo "  run status=${run_status_value} runId=${scan_run_id:-none}"
fi

if [[ -n "$scan_run_id" ]]; then
  echo "Polling autoscan state for run visibility..."
  for attempt in $(seq 1 30); do
    poll_body="$(mktemp)"
    poll_status="$(request_json GET "/api/v1/scans/autoscan/state" "$poll_body")"
    echo "  attempt ${attempt}: ${poll_status}"

    if [[ "$poll_status" == "500" || "$poll_status" == "000" ]]; then
      echo "  ! Polling failed."
      failed=1
      rm -f "$poll_body"
      break
    fi

    if [[ "$poll_status" -ge 400 ]]; then
      require_structured_error "$poll_body"
      rm -f "$poll_body"
      break
    fi

    if jq -e --arg runId "$scan_run_id" '
      ((.runningRun != null and .runningRun.id == $runId)
      or (.lastRun != null and .lastRun.id == $runId)
      or (.recentRuns | map(.id) | index($runId) != null))
    ' "$poll_body" >/dev/null 2>&1; then
      echo "  run is visible in autoscan runtime state."
      rm -f "$poll_body"
      break
    fi

    rm -f "$poll_body"
    sleep 1
  done
fi

rm -f "$state_body" "$enable_body" "$state2_body" "$run_body"

if [[ "$failed" -ne 0 ]]; then
  echo "Autoscan smoke check failed."
  exit 1
fi

echo "Autoscan smoke check passed."
