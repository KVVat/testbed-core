#!/bin/bash

OUT_FILE="/tmp/mcp_sse_out.json"
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
echo "Endpoint: $MSG_ENDPOINT"

echo -e "\n--- Requesting junit_test_list ---"
curl -s -X POST "$MSG_ENDPOINT" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"tools/call","id":1001,"params":{"name":"junit_test_list","arguments":{}}}'

sleep 3

echo -e "\n--- SSE Response Stream ---"
cat "$OUT_FILE"

kill $SSE_PID 2>/dev/null
rm -f "$OUT_FILE"
