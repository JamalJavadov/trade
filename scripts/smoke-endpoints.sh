#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
ENDPOINTS=(
  "/api/v1/ai/models"
  "/api/v1/status"
  "/api/v1/settings"
  "/api/v1/control-center/state"
  "/api/v1/recommendations/latest"
)

allowed_non_2xx=("401" "403" "422" "429" "503")
failed=0

contains() {
  local seek="$1"
  shift
  for item in "$@"; do
    if [[ "$item" == "$seek" ]]; then
      return 0
    fi
  done
  return 1
}

for endpoint in "${ENDPOINTS[@]}"; do
  body_file="$(mktemp)"
  status="$(curl -sS --max-time 10 -o "$body_file" -w "%{http_code}" "${BASE_URL}${endpoint}" || true)"

  echo "${endpoint} -> ${status}"

  if [[ "$status" == "000" ]]; then
    echo "  ! Request failed before receiving an HTTP response."
    failed=1
    rm -f "$body_file"
    continue
  fi

  if [[ "$status" == "500" ]]; then
    echo "  ! Unexpected HTTP 500"
    failed=1
  fi

  if [[ "$status" -ge 400 ]]; then
    if ! contains "$status" "${allowed_non_2xx[@]}"; then
      echo "  ! Unexpected non-2xx status: ${status}"
      failed=1
    fi

    if ! jq -e '
      (.path | type == "string")
      and (.errorCode | type == "string")
      and (.message | type == "string")
      and ((.traceId | type) == "string" or .traceId == null)
    ' "$body_file" >/dev/null 2>&1; then
      echo "  ! Non-2xx response is not a structured API error payload."
      failed=1
    fi

    if [[ "$status" == "503" ]]; then
      if ! jq -e '.errorCode == "DB_DOWN"' "$body_file" >/dev/null 2>&1; then
        echo "  ! HTTP 503 payload does not map to errorCode=DB_DOWN."
        failed=1
      fi
    fi
  fi

  rm -f "$body_file"
done

if [[ "$failed" -ne 0 ]]; then
  echo "Smoke check failed."
  exit 1
fi

echo "Smoke check passed."
