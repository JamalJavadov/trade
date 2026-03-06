#!/bin/bash
set -e

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
./gradlew bootRun > backend.log 2>&1 &
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
echo "  Backend:  http://localhost:8080"
echo ""
echo "To stop: ./stop-all.sh"
echo "====================================="

wait
