#!/bin/bash

CLASS="org.example.plugin.fiax509.FiaX509RevocationTest"

OUT_FILE="/tmp/mcp_run_single_sse_out.json"
rm -f "$OUT_FILE"

curl -sN http://localhost:11452/mcp > "$OUT_FILE" &
SSE_PID=$!
sleep 2

SESSION_INFO=$(grep "^data: " "$OUT_FILE" | head -n 1 | sed 's/^data: //' | tr -d '\r')
if [ -z "$SESSION_INFO" ]; then
    echo "Failed to connect to MCP server"
    kill $SSE_PID 2>/dev/null
    exit 1
fi

MSG_ENDPOINT="http://localhost:11452${SESSION_INFO}"
LOG_PATH="/Users/wkouki/Library/Application Support/TestbedCore/main.log"

echo "=========================================="
echo "Triggering single test: $CLASS"
START_LINE=$(wc -l < "$LOG_PATH")

curl -s -X POST "$MSG_ENDPOINT" \
     -H "Content-Type: application/json" \
     -d "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"id\":1005,\"params\":{\"name\":\"junit_test_execute\",\"arguments\":{\"class_name\":\"$CLASS\"}}}"

echo "Waiting for test to finish..."
TIMEOUT=240
ELAPSED=0
FINISHED=false
while [ $ELAPSED -lt $TIMEOUT ]; do
    sleep 2
    ELAPSED=$((ELAPSED + 2))
    
    NEW_LOGS=$(tail -n +$((START_LINE + 1)) "$LOG_PATH")
    if echo "$NEW_LOGS" | grep -q "FINISH:"; then
        FINISHED=true
        echo "Test finished! (Time elapsed: ${ELAPSED}s)"
        break
    fi
done

if [ "$FINISHED" = false ]; then
    echo "WARNING: Test timed out after ${TIMEOUT}s!"
fi

kill $SSE_PID 2>/dev/null
rm -f "$OUT_FILE"
