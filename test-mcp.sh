#!/bin/bash
# SSEエンドポイントは / (root)
curl -sN http://localhost:11452/mcp > /tmp/sse_out &
SSE_PID=$!

echo "Waiting for SSE connection..."
sleep 2

# Format: data: ?sessionId=...
SESSION_QUERY=$(grep "^data:" /tmp/sse_out | head -n 1 | sed 's/^data: //')

if [ -z "$SESSION_QUERY" ]; then
    echo "Raw SSE output:"
    cat /tmp/sse_out
    echo ""
    echo "Failed to get session URI"
    kill $SSE_PID 2>/dev/null
    exit 1
fi

MSG_ENDPOINT="http://localhost:11451/message${SESSION_QUERY}"
echo "Got message endpoint: $MSG_ENDPOINT"

# 2. tools/list
echo "--- Calling tools/list ---"
curl -s -X POST "$MSG_ENDPOINT" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
echo -e "\n"
sleep 1

# 3. get_device_info
echo "--- Calling get_device_info ---"
curl -s -X POST "$MSG_ENDPOINT" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"tools/call","id":2,"params":{"name":"get_device_info","arguments":{}}}'
echo -e "\n"

sleep 2

# Check what we received on the SSE channel
echo "--- SSE responses received ---"
cat /tmp/sse_out

kill $SSE_PID 2>/dev/null
