# 便携式图形化构建器使用说明

## 最简单的使用方式

在 Windows 10/11 上解压工程后，直接双击根目录：

```text
打开图形化构建器.bat
```

图形界面本身使用 Windows 自带的 PowerShell + WinForms，因此第一次启动不要求电脑预装 Python、JDK、Git、Android Studio、Android SDK、NDK 或 CMake。

点击“开始构建 APK”时，缺少的构建前置会自动下载并安装到：

```text
portable_workspace/
```

也可以先点“准备 / 修复便携环境”，单独完成前置工具下载。

## 工程内目录隔离

便携环境的主要目录：

```text
portable_workspace/
├─ runtime/
│  ├─ python/          便携 Python
│  ├─ jdk/             Eclipse Temurin JDK 21
│  ├─ android-sdk/     Command-line Tools、平台、Build Tools、NDK、CMake
│  └─ gradle-9.4.1/    Gradle 运行时
├─ downloads/          前置工具下载缓存
├─ cache/              RFB、curl、Mbed TLS、CA bundle 等下载缓存
├─ gradle-home/        Gradle 依赖和构建缓存
├─ android-user-home/  Android 工具用户状态
├─ home/               构建进程 HOME
└─ temp/               临时文件
```

项目源码、生成文件、native 构建中间产物和 APK 输出也都位于工程目录树内部：

```text
vendor/
generated/
app/build/
dist/
```

构建器不会写系统 PATH，不修改注册表，不要求管理员权限，也不会安装 Android Studio。

## 为什么能放在中文、空格或较深的目录

所有脚本使用自身所在目录计算工程根目录，不写死盘符和绝对路径。启动真实 NDK/CMake 构建时，Windows 版会为当前工程建立一个临时 `SUBST` 短盘符别名，例如：

```text
R:\  →  D:\游戏开发\RFB 安卓移植工程\...
```

实际文件仍然完全留在原工程目录，没有复制到 C 盘或用户目录。构建正常结束会在 `finally` 中删除临时盘符；GUI 强制取消或退出时也会尝试清理指向当前工程的临时别名。

## 环境来源

便携构建器会准备：

- Python Windows embeddable package；
- Eclipse Temurin JDK 21 ZIP 归档；
- Android SDK Command-line Tools；
- `sdkmanager` 安装的 Android 36、Build Tools 36.0.0、NDK r29、CMake 3.22.1；
- Gradle 9.4.1；
- RFB 最新正式 Release（或指定标签/本地源码）的源码归档；
- curl、Mbed TLS 和 CA bundle。

RFB 默认下载不依赖 Git。若选择“使用本地 RFB 源码”，构建器只把指定源码复制到本工程的 `vendor/rfb` 再构建，不会修改原源码目录。

## 图形界面

界面支持：

- Debug / Release（未签名）构建；
- 自动解析并下载最新正式 RFB Release，也可固定标签；
- 离线复用最后一次成功解析的最新版本；
- 选择本地 RFB 源码；
- 一键准备/修复便携环境；
- 实时显示下载、SDK 安装、源码准备、Gradle、CMake、NDK 编译日志；
- 构建阶段进度条；
- 取消整个构建进程树；
- 打开 `portable_workspace/`；
- 打开 `dist/` APK 输出目录。

## 首次运行为什么比较慢

第一次会下载 JDK、Android Command-line Tools、Android 平台、NDK、CMake、Gradle、RFB 源码和 native 第三方依赖。之后它们都保留在工程目录内，下次构建会复用。

移动整个工程文件夹到另一个目录或磁盘后，可以直接重新打开 GUI；路径均在运行时重新计算。`local.properties` 也会按当前位置重新生成。
