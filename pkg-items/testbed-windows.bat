@echo off
setlocal EnableExtensions

set "TOOL_DIR=%~dp0"

if not exist "%TOOL_DIR%bin\platform-tools\adb.exe" (
    echo [Launcher] ADB not found. Running setup...
    call "%TOOL_DIR%scripts\setup_tools.bat"
)

echo [Launcher] Setting temporary PATH...
set "PATH=%TOOL_DIR%bin\platform-tools;%PATH%"

set "ANDROID_SDK_ROOT=%TOOL_DIR%bin"
set "ANDROID_HOME=%TOOL_DIR%bin"

echo [Launcher] where adb:
where adb

set "EXE_PATH=%TOOL_DIR%TestbedCore.exe"
if exist "%EXE_PATH%" (
    echo [Launcher] Starting TestBed Core...
    cd /D "%TOOL_DIR%"
    "%EXE_PATH%"
) else (
    echo [Error] Application not found: %EXE_PATH%
    pause
)

endlocal