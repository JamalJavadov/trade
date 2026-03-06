#!/bin/bash

echo "Stopping TradeBot Operator Stack..."

# Kill Node processes (vite/ui)
pkill -f "vite" || true

# Kill Java processes (spring boot)
pkill -f "tradebot" || true
pkill -f "gradle" || true


echo "All services gracefully stopped."
