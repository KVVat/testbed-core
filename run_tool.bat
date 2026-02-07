@echo off
setlocal

:: ==========================================
:: TestBed Core Portable Launcher for Windows
:: ==========================================

:: カレントディレクトリ（プロジェクトルート）を取得
set TOOL_DIR=%~dp0

:: 1. ADBの存在確認
:: bin/platform-tools/adb.exe がなければ、セットアップスクリプトを呼び出してダウンロードさせる
if not exist "%TOOL_DIR%bin\platform-tools\adb.exe" (
    echo [Launcher] ADB not found. Running setup...
    call "%TOOL_DIR%scripts\setup_tools.bat"
)

:: 2. ADBへのパスを一時的に通す
:: このコマンドプロンプト（プロセス）内でのみ有効。システム環境変数は汚さない。
echo [Launcher] Setting temporary PATH...
set PATH=%TOOL_DIR%bin\platform-tools;%PATH%

:: 動作確認（デバッグ用）
adb version

:: 3. アプリケーションの起動
:: ※配布時は build/libs/ 以下のJARファイル名を実際のファイル名に合わせてください
set JAR_PATH=%TOOL_DIR%build\libs\testbed-core-1.0.0.jar

if exist "%JAR_PATH%" (
    echo [Launcher] Starting TestBed Core...
    java -jar "%JAR_PATH%"
) else (
    echo [Error] Application JAR not found: %JAR_PATH%
    echo Please build the project (gradlew jar) or check the path in run_tool.bat.
    pause
)

endlocal

