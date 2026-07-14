@echo off
setlocal EnableExtensions
pushd "%~dp0" >nul 2>nul
if errorlevel 1 (
    echo ERROR: Cannot enter the project folder.
    pause
    exit /b 3
)
title RoguelikeFansBand Android Portable Debug Build
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%CD%\tools\portable_build.ps1" -Variant debug %*
set "RC=%errorlevel%"
echo.
if "%RC%"=="0" (
    echo BUILD FINISHED SUCCESSFULLY.
    echo APK output: "%CD%\dist"
) else (
    echo BUILD FAILED. Exit code: %RC%
)
echo.
pause
popd
exit /b %RC%
