#!/bin/bash
set -e

# ==============================================
# TestBed Core Portable Launcher for Ubuntu build
# ==============================================

# pwd
SCRIPT_DIR=$(cd $(dirname "$0"); pwd)

# 1. Check local adb 
# bin/platform-tools/adb 
if [ ! -f "$SCRIPT_DIR/bin/platform-tools/adb" ]; then
    echo "[Launcher] ADB not found. Running setup..."
    bash "$SCRIPT_DIR/scripts/setup_tools.sh"
fi

# 2. use local adb tool
echo "[Launcher] Setting temporary PATH..."
export PATH="$SCRIPT_DIR/bin/platform-tools:$PATH"

# confirmation
adb version

# 3. launch application
EXE_PATH="$SCRIPT_DIR/main-release/app/TestbedCore/bin/TestbedCore"

if [ -f "$EXE_PATH" ]; then
  　chmod +x "$EXE_PATH"
    echo "[Launcher] Starting TestBed Core..."
    "$EXE_PATH" &
else
    echo "[Error] Application Executable not found at: $EXE_PATH"
    exit 1
fi
