#!/bin/bash
# SDK Platform-Tools Auto Install Script
set -e
TOOLS_DIR="bin"
PLATFORM_TOOLS_DIR="$TOOLS_DIR/platform-tools"
OS_TYPE="$(uname -s)"

mkdir -p "$TOOLS_DIR"

if [ ! -d "$PLATFORM_TOOLS_DIR" ]; then
    echo "Installing SDK Platform-Tools..."

    if [ "$OS_TYPE" == "Darwin" ]; then
        URL="https://dl.google.com/android/repository/platform-tools-latest-darwin.zip"
    else
        URL="https://dl.google.com/android/repository/platform-tools-latest-linux.zip"
    fi

    curl -L "$URL" -o "$TOOLS_DIR/tools.zip"
    unzip -q "$TOOLS_DIR/tools.zip" -d "$TOOLS_DIR/"
    rm "$TOOLS_DIR/tools.zip"

    mkdir -p "$PLATFORM_TOOLS_DIR/licenses"
    echo -e "8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb74380255e2f3ef351d\n24333f8a63b6825ea9c55727f47ce90465ef0ce0" > "$PLATFORM_TOOLS_DIR/licenses/android-sdk-license"

    echo "✅ Platform-Tools installed and licenses accepted in $PLATFORM_TOOLS_DIR"
else
    echo "✅ Platform-Tools already exists."
fi

