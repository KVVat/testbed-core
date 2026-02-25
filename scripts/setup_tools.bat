@echo off
setlocal EnableExtensions

REM ---- base: this .bat location (scripts\) ----
set "BASE_DIR=%~dp0"
REM bin をプロジェクトルート直下に作りたいなら scripts\..\bin
set "TOOLS_DIR=%BASE_DIR%..\bin"
set "PLATFORM_TOOLS_DIR=%TOOLS_DIR%\platform-tools"
set "ZIP_PATH=%TOOLS_DIR%\platform-tools.zip"
set "URL=https://dl.google.com/android/repository/platform-tools-latest-windows.zip"

echo [debug] CD=%CD%
echo [debug] BASE_DIR=%BASE_DIR%
echo [debug] TOOLS_DIR=%TOOLS_DIR%
echo [debug] URL=%URL%

if not exist "%TOOLS_DIR%" mkdir "%TOOLS_DIR%"

if not exist "%PLATFORM_TOOLS_DIR%" (
  echo Installing SDK Platform-Tools...

  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop';" ^
    "$url = '%URL%';" ^
    "$zip = '%ZIP_PATH%';" ^
    "$dst = '%TOOLS_DIR%';" ^
    "Invoke-WebRequest -Uri $url -OutFile $zip;" ^
    "Expand-Archive -Path $zip -DestinationPath $dst -Force"

  if errorlevel 1 (
    echo [error] Download/Extract failed.
    exit /b 1
  )

  if exist "%ZIP_PATH%" del "%ZIP_PATH%"

 if not exist "%PLATFORM_TOOLS_DIR%\licenses" mkdir "%PLATFORM_TOOLS_DIR%\licenses"
    echo 8933bad161af4178b1185d1a37fbf41ea5269c55 > "%PLATFORM_TOOLS_DIR%\licenses\android-sdk-license"
    echo d56f5187479451eabf01fb74380255e2f3ef351d >> "%PLATFORM_TOOLS_DIR%\licenses\android-sdk-license"
    echo 24333f8a63b6825ea9c55727f47ce90465ef0ce0 >> "%PLATFORM_TOOLS_DIR%\licenses\android-sdk-license"



  echo ✅ Platform-Tools installed in %PLATFORM_TOOLS_DIR%
) else (
  echo ✅ Platform-Tools already exists.
)

endlocal