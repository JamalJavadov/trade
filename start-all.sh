#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_CONFIG="${ROOT_DIR}/src/main/resources/application.yml"

BACKEND_PORT="${BACKEND_PORT:-8080}"
BACKEND_HOST="${BACKEND_HOST:-localhost}"
BACKEND_ORIGIN="${BACKEND_ORIGIN:-http://${BACKEND_HOST}:${BACKEND_PORT}}"
PID_FILE="/tmp/tradebot-pids"

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

DB_JDBC_URL="${SPRING_DATASOURCE_URL:-$DEFAULT_JDBC_URL}"
DB_USER="${SPRING_DATASOURCE_USERNAME:-$DEFAULT_DB_USER}"
DB_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-$DEFAULT_DB_PASSWORD}"

if [[ "$DB_JDBC_URL" =~ ^jdbc:postgresql://([^:/]+)(:([0-9]+))?/([^?]+)$ ]]; then
    DB_HOST="${BASH_REMATCH[1]}"
    DB_PORT="${BASH_REMATCH[3]:-5432}"
    DB_NAME="${BASH_REMATCH[4]}"
else
    echo "Unable to parse spring.datasource.url from ${APP_CONFIG}: ${DB_JDBC_URL}"
    exit 1
fi

echo "Starting TradeBot Operator Stack..."

# 1. Verify Postgres is running
echo "[1/3] Checking Postgres..."
echo "  Expecting datasource host=${DB_HOST} port=${DB_PORT} db=${DB_NAME} user=${DB_USER}"
if command -v pg_isready > /dev/null 2>&1; then
    if ! pg_isready -h "$DB_HOST" -p "$DB_PORT" > /dev/null 2>&1; then
        echo "  PostgreSQL is not reachable at ${DB_HOST}:${DB_PORT}."
        echo "  Start the configured PostgreSQL instance, then re-run this script."
        exit 1
    fi
else
    echo "  pg_isready not found; skipping socket-level readiness probe."
fi

if ! PGPASSWORD="$DB_PASSWORD" psql -X -v ON_ERROR_STOP=1 -q -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
    -c "select current_database(), current_user;" > /dev/null 2>&1; then
    echo "  PostgreSQL is reachable, but the configured datasource credentials/database failed."
    echo "  Verify spring.datasource.url, spring.datasource.username, and spring.datasource.password in ${APP_CONFIG}."
    exit 1
fi
echo "  Datasource preflight succeeded."

# 2. Kill any leftover process already holding the port
if lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN > /dev/null 2>&1; then
    echo "  Port $BACKEND_PORT is in use — killing existing process..."
    lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN -t | xargs kill -9 2>/dev/null || true
    sleep 1
fi

# 3. Start Backend API
echo "[2/3] Starting Spring Boot Backend API..."
export BACKEND_PORT
export BACKEND_HOST
export BACKEND_ORIGIN
export VITE_BACKEND_PORT="$BACKEND_PORT"
export VITE_BACKEND_HOST="$BACKEND_HOST"
export VITE_BACKEND_ORIGIN="$BACKEND_ORIGIN"

./gradlew bootRun --args="--server.port=${BACKEND_PORT}" > backend.log 2>&1 &
BACKEND_PID=$!
echo "  Backend PID: $BACKEND_PID (logs: backend.log)"

# 4. Start Frontend UI
echo "[3/3] Starting UI Dashboard..."
cd "${ROOT_DIR}/ui"
npm install --silent
npm run dev > ../ui.log 2>&1 &
UI_PID=$!
echo "  UI PID: $UI_PID (logs: ui.log)"
cd "$ROOT_DIR"

# Save PIDs for stop-all.sh
echo "$BACKEND_PID $UI_PID" > "$PID_FILE"

echo ""
echo "====================================="
echo "TradeBot is running."
echo "  UI:       http://localhost:5173"
echo "  Backend:  ${BACKEND_ORIGIN}"
echo ""
echo "To stop: ./stop-all.sh"
echo "====================================="

wait
