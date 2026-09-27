#!/bin/bash
# Runs the engine test page in headless Chromium and prints its output.
cd "$(dirname "$0")/.."
LOG=$(mktemp)
python3 test/serve.py 8765 > "$LOG" 2>&1 & SERVER=$!
sleep 1
timeout "${1:-20}" chromium --headless=new --autoplay-policy=no-user-gesture-required \
    --user-data-dir="$(mktemp -d)" "http://127.0.0.1:8765/test/index.html?auto" >/dev/null 2>&1
kill $SERVER
cat "$LOG"; rm -f "$LOG"
