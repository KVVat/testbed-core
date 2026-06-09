#!/bin/bash

CLASSES=(
    "org.example.plugin.fdptudext.FdpTudExtTest"
    "org.example.plugin.ftpitc.FtpItecExt1"
    "org.example.plugin.fdpacc.FdpAcc1Test"
    "org.example.plugin.fcsckh.FcsCkhExt1HighCredentialsTest"
    "org.example.plugin.fiax509.FiaX509ExtTest"
    "org.example.plugin.fiax509.FiaX509RevocationTest"
    "org.example.plugin.fiax509.FiaX509ExtensionsTest"
    "org.example.plugin.fiax509.FiaX509RevocationUnreachableTest"
    "org.example.plugin.fiax509.FiaX509ContextUsageTest"
    "org.example.plugin.fiax509.FiaX509ValidatorTest"
    "org.example.plugin.fiax509.FiaX509TrustStoreTest"
    "org.example.plugin.fptaex.FptAexExt4Test"
    "org.example.plugin.LongRunningTest"
    "org.example.plugin.fcstls.FcsTlscExt3PoodleTest"
    "org.example.plugin.fcstls.FcsTlscExt1LegacyRejectionTest"
    "org.example.plugin.fcstls.FcsTlscExtTest"
    "org.example.plugin.fcstls.FcsTlscExt3SignalTest"
    "org.example.plugin.fcstls.FcsTlscExt4RenegotiationTest"
    "org.example.plugin.fiax509.CertManagerTest"
    "org.example.plugin.fiax509.NiapValidatorTest"
)

OUT_FILE="/tmp/mcp_run_sse_out.json"
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

for CLASS in "${CLASSES[@]}"; do
    echo "=========================================="
    echo "Triggering test: $CLASS"
    
    START_LINE=$(wc -l < "$LOG_PATH")
    
    curl -s -X POST "$MSG_ENDPOINT" \
         -H "Content-Type: application/json" \
         -d "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"id\":1002,\"params\":{\"name\":\"junit_test_execute\",\"arguments\":{\"class_name\":\"$CLASS\"}}}"
    
    echo "Waiting for test to finish..."
    TIMEOUT=180
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
done

kill $SSE_PID 2>/dev/null
rm -f "$OUT_FILE"
echo "All tests execution requested."
