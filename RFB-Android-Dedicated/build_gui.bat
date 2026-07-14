@echo off
setlocal EnableExtensions
pushd "%~dp0" >nul 2>nul
if errorlevel 1 (
    echo.
    echo ERROR: Cannot enter the project folder.
    echo.
    pause
    exit /b 3
)
title RoguelikeFansBand Android Portable Builder v7.5 Latest
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%CD%\tools\portable_gui.ps1"
set "RC=%errorlevel%"
if not "%RC%"=="0" (
    echo.
    echo GUI launcher failed. Exit code: %RC%
    echo Please send the text above when reporting the problem.
    echo.
    pause
)
popd
exit /b %RC%
