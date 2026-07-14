@echo off
setlocal EnableExtensions
cd /d "%~dp0"
set "ADB=%~dp0portable_workspace\runtime\android-sdk\platform-tools\adb.exe"
set "LOGDIR=%~dp0portable_workspace\logs"
set "LOGFILE=%LOGDIR%\android-runtime-logcat.txt"
set "STARTUPLOG=%LOGDIR%\rfb-startup.txt"

if not exist "%ADB%" (
  echo ERROR: portable adb was not found:
  echo %ADB%
  echo Run the portable environment preparation/build once first.
  pause
  exit /b 1
)

if not exist "%LOGDIR%" mkdir "%LOGDIR%"
"%ADB%" start-server >nul 2>&1
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  echo ERROR: no authorized Android device is connected.
  echo Enable USB debugging, connect the phone, and accept the authorization prompt.
  pause
  exit /b 2
)

"%ADB%" logcat -c
"%ADB%" shell am force-stop org.roguelikefansband.android >nul 2>&1
"%ADB%" shell am start -n org.roguelikefansband.android/.MainActivity

echo Waiting 10 seconds while the app starts...
timeout /t 10 /nobreak >nul
"%ADB%" logcat -d -v threadtime > "%LOGFILE%"
"%ADB%" exec-out run-as org.roguelikefansband.android cat files/rfb-startup.log > "%STARTUPLOG%" 2>nul
for %%A in ("%STARTUPLOG%") do if %%~zA==0 (
  "%ADB%" pull /sdcard/Android/data/org.roguelikefansband.android/files/rfb-startup.log "%STARTUPLOG%" >nul 2>&1
)

echo.
echo Runtime log saved to:
echo %LOGFILE%
echo Startup-stage log saved to:
echo %STARTUPLOG%
echo.
echo Send this file if the app still exits or crashes.
pause
