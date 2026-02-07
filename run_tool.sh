#!/bin/bash
set -e

# ==============================================
# TestBed Core Portable Launcher for macOS/Linux
# ==============================================

# スクリプトの場所（プロジェクトルート）を取得
SCRIPT_DIR=$(cd $(dirname "$0"); pwd)

# 1. ADBの存在確認
# bin/platform-tools/adb がなければ、セットアップスクリプトを実行
if [ ! -f "$SCRIPT_DIR/bin/platform-tools/adb" ]; then
    echo "[Launcher] ADB not found. Running setup..."
    bash "$SCRIPT_DIR/scripts/setup_tools.sh"
fi

# 2. ADBへのパスを一時的に通す
echo "[Launcher] Setting temporary PATH..."
export PATH="$SCRIPT_DIR/bin/platform-tools:$PATH"

# 動作確認
adb version

# 3. アプリケーションの起動
# ※配布時はJARファイルのパスと名前を実際の構成に合わせてください
#./gradlew :composeApp:packageUberJarForCurrentOS の MacOSでの出力先
JAR_PATH="$SCRIPT_DIR/composeApp/build/compose/jars/org.example.project-macos-arm64-1.0.0.jar"

if [ -f "$JAR_PATH" ]; then
    echo "[Launcher] Starting TestBed Core..."
    java -jar "$JAR_PATH"
else
    echo "[Error] Application JAR not found at: $JAR_PATH"
    echo "Please build the project (./gradlew jar) or check the path in run_tool.sh"
    exit 1
fi
