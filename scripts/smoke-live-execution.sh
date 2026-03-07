#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
TRACE_ID="smoke-live-execution-$(date +%s)"
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
    curl -sS --max-time 20 -X "$method" \
      -H "Content-Type: application/json" \
      -H "X-Trace-Id: ${TRACE_ID}" \
      -o "$body_file" -w "%{http_code}" \
      "${BASE_URL}${path}" \
      --data "$payload" || true
  else
    curl -sS --max-time 20 -X "$method" \
      -H "X-Trace-Id: ${TRACE_ID}" \
      -o "$body_file" -w "%{http_code}" \
      "${BASE_URL}${path}" || true
  fi
}

cleanup_live_trading() {
  local payload='{"patch":{"permissions":{"live.execution.enabled":false},"liveExecution":{"readOnly":true}},"reason":"smoke-live-execution-cleanup"}'
  local body_file
  body_file="$(mktemp)"
  request_json POST "/api/v1/control-center/state" "$body_file" "$payload" >/dev/null || true
  rm -f "$body_file"
}

trap cleanup_live_trading EXIT

recommendation_id=""
latest_body="$(mktemp)"
latest_status="$(request_json GET "/api/v1/recommendations/latest" "$latest_body")"
echo "GET /api/v1/recommendations/latest -> ${latest_status}"

if [[ "$latest_status" == "204" ]]; then
  run_body="$(mktemp)"
  run_status="$(request_json POST "/api/v1/scans/run-once" "$run_body" "{}")"
  echo "POST /api/v1/scans/run-once -> ${run_status}"
  rm -f "$run_body"

  for attempt in $(seq 1 30); do
    retry_body="$(mktemp)"
    retry_status="$(request_json GET "/api/v1/recommendations/latest" "$retry_body")"
    echo "  recommendation poll ${attempt}: ${retry_status}"
    if [[ "$retry_status" == "200" ]]; then
      recommendation_id="$(jq -r '.id // empty' "$retry_body" 2>/dev/null || true)"
      rm -f "$retry_body"
      break
    fi
    rm -f "$retry_body"
    sleep 2
  done
elif [[ "$latest_status" -ge 400 ]]; then
  require_structured_error "$latest_body"
else
  recommendation_id="$(jq -r '.id // empty' "$latest_body" 2>/dev/null || true)"
fi
rm -f "$latest_body"

if [[ -z "$recommendation_id" ]]; then
  echo "  ! No recommendation available for live execution smoke test."
  exit 1
fi

enable_payload='{"patch":{"permissions":{"live.execution.enabled":true},"liveExecution":{"readOnly":false}},"reason":"smoke-live-execution-enable"}'
enable_body="$(mktemp)"
enable_status="$(request_json POST "/api/v1/control-center/state" "$enable_body" "$enable_payload")"
echo "POST /api/v1/control-center/state (enable manual live execution and clear READ-ONLY) -> ${enable_status}"
if [[ "$enable_status" -ge 400 ]]; then
  require_structured_error "$enable_body"
  rm -f "$enable_body"
  exit 1
fi
rm -f "$enable_body"

health_body="$(mktemp)"
health_status="$(request_json GET "/api/v1/live-trading/health?symbol=BTCUSDT" "$health_body")"
echo "GET /api/v1/live-trading/health?symbol=BTCUSDT -> ${health_status}"
if [[ "$health_status" -ge 400 ]]; then
  require_structured_error "$health_body"
  rm -f "$health_body"
  exit 1
fi
if ! jq -e '
    (.runtime.liveExecutionEnabled | type == "boolean")
    and (.runtime.readOnly | type == "boolean")
    and (.localRequest.allowed | type == "boolean")
    and (.binance.endpointFamily | type == "string")
    and (.blockedReasons | type == "array")
  ' "$health_body" >/dev/null 2>&1; then
  echo "  ! Live-trading health response is missing structured readiness fields."
  failed=1
fi
rm -f "$health_body"

preflight_body="$(mktemp)"
preflight_status="$(request_json GET "/api/v1/recommendations/${recommendation_id}/execution-preflight" "$preflight_body")"
echo "GET /api/v1/recommendations/${recommendation_id}/execution-preflight -> ${preflight_status}"
if [[ "$preflight_status" -ge 400 ]]; then
  require_structured_error "$preflight_body"
  rm -f "$preflight_body"
  exit 1
fi

if ! jq -e '.executionEnabled == true and (.runtime.readOnly == false) and (.blockedReasons | type == "array")' "$preflight_body" >/dev/null 2>&1; then
  echo "  ! Preflight response did not confirm live execution capability."
  failed=1
fi
rm -f "$preflight_body"

request_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"
execute_payload="{\"clientRequestId\":\"${request_id}\",\"operatorNote\":\"smoke-live-execution\"}"
execute_body="$(mktemp)"
execute_status="$(request_json POST "/api/v1/recommendations/${recommendation_id}/execute-live" "$execute_body" "$execute_payload")"
echo "POST /api/v1/recommendations/${recommendation_id}/execute-live -> ${execute_status}"
if [[ "$execute_status" -ge 400 ]]; then
  require_structured_error "$execute_body"
  rm -f "$execute_body"
  exit 1
fi

execution_id="$(jq -r '.id // empty' "$execute_body" 2>/dev/null || true)"
execution_state="$(jq -r '.executionState // empty' "$execute_body" 2>/dev/null || true)"
echo "  execution id=${execution_id:-none} state=${execution_state:-none}"
if [[ -z "$execution_id" ]]; then
  echo "  ! Execute-live response did not return an execution id."
  failed=1
fi
rm -f "$execute_body"

list_body="$(mktemp)"
list_status="$(request_json GET "/api/v1/live-trading/executions?recommendationId=${recommendation_id}&limit=1" "$list_body")"
echo "GET /api/v1/live-trading/executions?recommendationId=${recommendation_id}&limit=1 -> ${list_status}"
if [[ "$list_status" -ge 400 ]]; then
  require_structured_error "$list_body"
else
  if ! jq -e 'type == "array"' "$list_body" >/dev/null 2>&1; then
    echo "  ! Execution list response is not an array."
    failed=1
  fi
fi
rm -f "$list_body"

detail_body="$(mktemp)"
detail_status="$(request_json GET "/api/v1/live-trading/executions/${execution_id}" "$detail_body")"
echo "GET /api/v1/live-trading/executions/${execution_id} -> ${detail_status}"
if [[ "$detail_status" -ge 400 ]]; then
  require_structured_error "$detail_body"
else
  if ! jq -e --arg id "$execution_id" '.id == $id' "$detail_body" >/dev/null 2>&1; then
    echo "  ! Execution detail response does not match requested id."
    failed=1
  fi
fi
rm -f "$detail_body"

if [[ "$failed" -ne 0 ]]; then
  echo "Live execution smoke check failed."
  exit 1
fi

echo "Live execution smoke check passed."
