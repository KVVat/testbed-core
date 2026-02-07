@echo off
REM SDK Platform-Tools 自動インストールスクリプト (Windows用)
REM PowerShellを使用してダウンロードと解凍を行います。

set TOOLS_DIR=bin
set PLATFORM_TOOLS_DIR=%TOOLS_DIR%\platform-tools

if not exist %TOOLS_DIR% mkdir %TOOLS_DIR%

if not exist %PLATFORM_TOOLS_DIR% (
    echo Installing SDK Platform-Tools...
    set URL=https://dl.google.com/android/repository/platform-tools-latest-windows.zip

    powershell -Command "Invoke-WebRequest -Uri '%URL%' -OutFile '%TOOLS_DIR%\tools.zip'"
    powershell -Command "Expand-Archive -Path '%TOOLS_DIR%\tools.zip' -DestinationPath '%TOOLS_DIR%'"
    del %TOOLS_DIR%\tools.zip

    REM --- ライセンス同意の自動化 ---
    if not exist "%PLATFORM_TOOLS_DIR%\licenses" mkdir "%PLATFORM_TOOLS_DIR%\licenses"
    echo 8933bad161af4178b1185d1a37fbf41ea5269c55 > "%PLATFORM_TOOLS_DIR%\licenses\android-sdk-license"
    echo d56f5187479451eabf01fb74380255e2f3ef351d >> "%PLATFORM_TOOLS_DIR%\licenses\android-sdk-license"
    echo 24333f8a63b6825ea9c55727f47ce90465ef0ce0 >> "%PLATFORM_TOOLS_DIR%\licenses\android-sdk-license"

    echo ✅ Platform-Tools installed and licenses accepted in %PLATFORM_TOOLS_DIR%
) else (
    echo ✅ Platform-Tools already exists.
)
