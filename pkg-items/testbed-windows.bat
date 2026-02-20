@echo off
setlocal

:: =========================================
:: TestBed Core Portable Launcher for Windows
:: =========================================

set TOOL_DIR=%~dp0

:: 1. ADB check
:: If bin/platform-tools/adb.exe is missing, call the setup script.
if not exist "%TOOL_DIR%bin\platform-tools\adb.exe" (
    echo [Launcher] ADB not found. Running setup...
    call "%TOOL_DIR%scripts\setup_tools.bat"
)

:: 2. Set temporary PATH
:: This is only valid for this command prompt session.
echo [Launcher] Setting temporary PATH...
set PATH=%TOOL_DIR%bin\platform-tools;%PATH%

:: 3. Launch Application
set EXE_PATH=%TOOL_DIR%testbed-core.exe

if exist "%EXE_PATH%" (
    echo [Launcher] Starting TestBed Core...
    start "" "%EXE_PATH%"
) else (
    echo [Error] Application not found: %EXE_PATH%
    pause
)

endlocal