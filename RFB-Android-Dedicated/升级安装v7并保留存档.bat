@echo off
setlocal EnableExtensions
chcp 65001 >nul

set "ROOT=%~dp0"
set "PACKAGE=org.roguelikefansband.android"
set "ADB=%ROOT%portable_workspace\runtime\android-sdk\platform-tools\adb.exe"
set "APK="
for /f "delims=" %%F in ('dir /b /a-d /o-d "%ROOT%dist\RoguelikeFansBand-Android-*-v*-universal-debug.apk" 2^>nul') do if not defined APK set "APK=%ROOT%dist\%%F"

rem 兼容使用早期 v7 构建脚本生成、但已经包含三 ABI 的旧文件名。
if not defined APK (
    for /f "delims=" %%F in ('dir /b /a-d /o-d "%ROOT%dist\RoguelikeFansBand-Android-*-v*-debug.apk" 2^>nul') do if not defined APK set "APK=%ROOT%dist\%%F"
)

if not exist "%ADB%" (
    where adb.exe >nul 2>nul
    if errorlevel 1 (
        echo [失败] 未找到 adb.exe。
        echo 请先在本工程运行“打开图形化构建器.bat”，点击“准备 / 修复便携环境”，
        echo 或者把 Android SDK platform-tools 加入 PATH，然后重新运行本脚本。
        pause
        exit /b 1
    )
    set "ADB=adb.exe"
)

if not defined APK (
    echo [失败] 未找到待安装 APK：
    echo %ROOT%dist\RoguelikeFansBand-Android-*-v*-universal-debug.apk
    echo 请先构建最新版 Debug APK，或把随工程提供的 universal-debug APK 放回 dist 目录。
    pause
    exit /b 1
)

echo 将安装最新生成的 APK：
echo %APK%

echo 请连接手机、开启 USB 调试，并在手机上允许本电脑调试。
echo 升级前请先在旧版游戏内用 Ctrl+X 保存退出，避免遗漏本轮尚未写入磁盘的进度。
pause
"%ADB%" start-server >nul
"%ADB%" get-state 1>nul 2>nul
if errorlevel 1 (
    echo [失败] 没有检测到已授权的 Android 设备。
    "%ADB%" devices
    pause
    exit /b 1
)

"%ADB%" shell run-as %PACKAGE% test -d files/rfb/lib/save 1>nul 2>nul
if errorlevel 1 (
    echo [失败] 无法读取旧版应用存档。请确认：
    echo   1. 手机中仍安装着此前提供的 v6.0 tested-debug APK；
    echo   2. 旧版至少成功启动过一次；
    echo   3. 尚未卸载旧版应用。
    echo 为安全起见，本脚本没有卸载任何内容。
    pause
    exit /b 1
)

for /f %%T in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%T"
set "BACKUP_DIR=%ROOT%portable_workspace\save-backups\%STAMP%"
set "BACKUP=%BACKUP_DIR%\rfb-writable-data.tar"
if not exist "%BACKUP_DIR%" mkdir "%BACKUP_DIR%"

echo [1/5] 从 v6 备份存档与用户数据到电脑……
"%ADB%" shell am force-stop %PACKAGE% >nul 2>nul
"%ADB%" exec-out run-as %PACKAGE% tar -C files/rfb lib/save lib/user lib/apex lib/bone lib/data > "%BACKUP%"
if errorlevel 1 goto :backup_failed
for %%F in ("%BACKUP%") do if %%~zF LSS 512 goto :backup_failed

echo [2/5] 卸载旧证书版本……
"%ADB%" uninstall %PACKAGE%
if errorlevel 1 goto :failed_with_backup

echo [3/5] 安装 v7 通用 APK……
"%ADB%" install "%APK%"
if errorlevel 1 goto :failed_with_backup

echo [4/5] 恢复存档与用户数据……
"%ADB%" shell run-as %PACKAGE% mkdir -p files/rfb/lib/save files/rfb/lib/user files/rfb/lib/apex files/rfb/lib/bone files/rfb/lib/data
if errorlevel 1 goto :failed_with_backup
set "REMOTE=/data/local/tmp/rfb-migration-%RANDOM%.tar"
"%ADB%" push "%BACKUP%" "%REMOTE%" >nul
if errorlevel 1 goto :failed_with_backup
"%ADB%" shell "cat %REMOTE% | run-as %PACKAGE% tar -C files/rfb -xf -"
if errorlevel 1 goto :restore_failed
"%ADB%" shell rm -f "%REMOTE%" >nul 2>nul

echo [5/5] 启动 v7……
"%ADB%" shell am start -n %PACKAGE%/.MainActivity
echo.
echo 升级完成。电脑端安全备份保留在：
echo %BACKUP%
echo 今后 v7 工程生成的 Debug APK 使用固定本地签名，可直接覆盖安装保留数据。
pause
exit /b 0

:backup_failed
echo [失败] 存档备份没有成功，因此没有卸载旧版应用。
pause
exit /b 1

:restore_failed
"%ADB%" shell rm -f "%REMOTE%" >nul 2>nul
echo [失败] v7 已安装，但自动恢复失败。请不要删除以下备份：
echo %BACKUP%
echo 可把本窗口内容和备份路径发来继续恢复。
pause
exit /b 1

:failed_with_backup
echo [失败] 升级中途停止。旧存档已经安全备份在：
echo %BACKUP%
echo 请不要删除该文件，可重新运行脚本或把错误日志发来。
pause
exit /b 1
