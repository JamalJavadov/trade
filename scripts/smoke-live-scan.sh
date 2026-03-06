#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
failed=0

require_structured_error() {
  local body_file="$1"
  if ! jq -e '
      (.path | type == "string")
      and (.errorCode | type == "string")
      and (.message | type == "string")
      and ((.traceId | type) == "string" or .traceId == null)
    ' "$body_file" >/dev/null 2>&1; then
    echo "  ! Non-2xx response is not a structured API error payload."
    failed=1
  fi
}

fetch_status() {
  local method="$1"
  local url="$2"
  local body_file="$3"
  curl -sS --max-time 10 -X "$method" -o "$body_file" -w "%{http_code}" "$url" || true
}

latest_body="$(mktemp)"
latest_status="$(fetch_status GET "${BASE_URL}/api/v1/scans/latest" "$latest_body")"
echo "/api/v1/scans/latest -> ${latest_status}"

if [[ "$latest_status" == "500" || "$latest_status" == "000" ]]; then
  echo "  ! Live scan state endpoint returned ${latest_status}"
  failed=1
elif [[ "$latest_status" -ge 400 ]]; then
  require_structured_error "$latest_body"
fi

start_body="$(mktemp)"
start_status="$(fetch_status POST "${BASE_URL}/api/v1/scans/run-once" "$start_body")"
echo "/api/v1/scans/run-once -> ${start_status}"

scan_run_id=""
if [[ "$start_status" == "500" || "$start_status" == "000" ]]; then
  echo "  ! Run-once endpoint returned ${start_status}"
  failed=1
elif [[ "$start_status" -ge 400 ]]; then
  require_structured_error "$start_body"
else
  scan_run_id="$(jq -r '.scanRunId // empty' "$start_body" 2>/dev/null || true)"
fi

if [[ -n "$scan_run_id" ]]; then
  echo "Polling /api/v1/scans/${scan_run_id} ..."
  for attempt in $(seq 1 20); do
    poll_body="$(mktemp)"
    poll_status="$(fetch_status GET "${BASE_URL}/api/v1/scans/${scan_run_id}" "$poll_body")"
    echo "  attempt ${attempt}: ${poll_status}"

    if [[ "$poll_status" == "500" || "$poll_status" == "000" ]]; then
      echo "  ! Poll endpoint returned ${poll_status}"
      failed=1
      rm -f "$poll_body"
      break
    fi

    if [[ "$poll_status" -ge 400 ]]; then
      require_structured_error "$poll_body"
      rm -f "$poll_body"
      break
    fi

    status_value="$(jq -r '.status // empty' "$poll_body" 2>/dev/null || true)"
    if [[ "$status_value" == "STARTED" || "$status_value" == "FINISHED" || "$status_value" == "FAILED" ]]; then
      echo "  status=${status_value}"
      rm -f "$poll_body"
      break
    fi

    rm -f "$poll_body"
    sleep 1
  done
fi

rm -f "$latest_body" "$start_body"

if [[ "$failed" -ne 0 ]]; then
  echo "Live scan smoke check failed."
  exit 1
fi

echo "Live scan smoke check passed."
