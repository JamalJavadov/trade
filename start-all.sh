#!/bin/bash
set -e

BACKEND_PORT="${BACKEND_PORT:-8080}"
BACKEND_HOST="${BACKEND_HOST:-localhost}"
BACKEND_ORIGIN="${BACKEND_ORIGIN:-http://${BACKEND_HOST}:${BACKEND_PORT}}"

echo "Starting TradeBot Operator Stack..."

# 1. Verify Postgres is running
echo "[1/3] Checking Postgres..."
if pgrep -f postgres > /dev/null 2>&1; then
    echo "  Postgres is already running."
else
    echo "  WARNING: Postgres does not appear to be running."
    echo "  Start it manually (e.g. 'pg_ctl start' or launch Postgres.app) then re-run this script."
    exit 1
fi

# 2. Start Backend API
echo "[2/3] Starting Spring Boot Backend API..."
if lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN > /tmp/tradebot-port-check.txt 2>/dev/null; then
    echo "  ERROR: Port $BACKEND_PORT is already in use."
    cat /tmp/tradebot-port-check.txt
    echo "  Stop the process above or rerun with BACKEND_PORT=<free-port>."
    rm -f /tmp/tradebot-port-check.txt
    exit 1
fi
rm -f /tmp/tradebot-port-check.txt

export BACKEND_PORT
export BACKEND_HOST
export BACKEND_ORIGIN
export VITE_BACKEND_PORT="$BACKEND_PORT"
export VITE_BACKEND_HOST="$BACKEND_HOST"
export VITE_BACKEND_ORIGIN="$BACKEND_ORIGIN"

./gradlew bootRun --args="--server.port=${BACKEND_PORT}" > backend.log 2>&1 &
BACKEND_PID=$!
echo "  Backend PID: $BACKEND_PID (logs: backend.log)"

# 3. Start Frontend UI
echo "[3/3] Starting UI Dashboard..."
cd ui
npm install --silent
npm run dev > ../ui.log 2>&1 &
UI_PID=$!
echo "  UI PID: $UI_PID (logs: ui.log)"
cd ..

echo ""
echo "====================================="
echo "TradeBot is running."
echo "  UI:       http://localhost:5173"
echo "  Backend:  ${BACKEND_ORIGIN}"
echo ""
echo "To stop: ./stop-all.sh"
echo "====================================="

wait
