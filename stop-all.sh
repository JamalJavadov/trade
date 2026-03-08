#!/bin/bash

BACKEND_PORT="${BACKEND_PORT:-8080}"
PID_FILE="/tmp/tradebot-pids"

echo "Stopping TradeBot Operator Stack..."

# Kill processes by saved PIDs if available
if [ -f "$PID_FILE" ]; then
    read -r BACKEND_PID UI_PID < "$PID_FILE"
    kill "$BACKEND_PID" 2>/dev/null || true
    kill "$UI_PID" 2>/dev/null || true
    rm -f "$PID_FILE"
fi

# Kill any remaining Vite / Gradle / bootRun processes
pkill -f "vite" 2>/dev/null || true
pkill -f "bootRun" 2>/dev/null || true
pkill -f "GradleWorkerMain" 2>/dev/null || true

# Kill anything still holding the backend port as a final safety net
lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN -t 2>/dev/null | xargs kill -9 2>/dev/null || true

echo "All services gracefully stopped."
