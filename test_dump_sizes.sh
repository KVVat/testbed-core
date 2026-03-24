#!/bin/bash
test_quality() {
    local q=$1
    echo "--- Testing image_quality = $q ---"
    OUT=$(./scripts/mcp_call.sh get_ui_dump "{\"include_image\":true, \"image_quality\":$q}")
    
    # Extract just the size of the string representation or byte count
    SIZE=$(echo "$OUT" | wc -c)
    echo "Total SSE Output Size: $SIZE bytes"
    
    # Extract base64 length if possible
    # We can try to grep "screenshot":"..."
    B64_LEN=$(echo "$OUT" | grep -o 'screenshot":"[^"]*' | wc -c)
    echo "Base64 payload approx size: $B64_LEN bytes"
    echo ""
}

test_quality 1
sleep 2
test_quality 2
sleep 2
test_quality 3

