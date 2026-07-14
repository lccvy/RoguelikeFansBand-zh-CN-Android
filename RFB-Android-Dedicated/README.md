# RoguelikeFansBand 专用 Android 工程

## v7.5 可操作存档目录与安静的正常退出

v7.5 修复部分 Android 系统只显示存档说明、却不显示可选列表的问题，并把正常退出与异常诊断彻底分开。构建器仍以 `latest` 跟进上游最新正式 Release。

- “存档槽”现在打开独立的可滚动存档目录，不再把说明文字和系统列表混在同一个弹窗中；
- 每个已有槽位都有明确的“打开”和“新游戏”按钮，当前运行槽显示“继续”；
- 顶部提供“新建存档并开始新游戏”，新槽会明确以新游戏模式进入；
- 正常保存退出只显示“已正常退出”，轻触即可重新打开存档目录，不再显示返回码和诊断路径；
- 只有异常退出或启动故障才显示诊断日志位置；日志采用两个 256KB 上限文件轮换，健康退出后自动删除，不会无限累积。

## v7.4 精确坐标与键盘避让

v7.4 在统一自由 HUD 上增加精确定位和键盘避让。构建器仍以 `latest` 跟进上游最新正式 Release，不绑定某一个游戏版本。

本版主要变化：

- 悬浮按键编辑界面新增 X/Y 坐标输入，以横屏左上角为 `(0,0)`，单位为 dp；相同 X 可纵向对齐，相同 Y 可横向对齐，保存时会按当前按钮尺寸检查有效范围；
- 拖拽布局继续保留，坐标输入用于精确摆放和对齐，两种定位方式共用同一份持久化布局；
- 呼出大键盘时，中心点位于游戏区的 HUD 按键会暂时隐藏并禁用，避免与键盘重叠；
- 位于保留操作区的 HUD 按键继续显示并可点击，关闭大键盘后游戏区按键自动恢复；
- 大键盘仍只覆盖游戏终端，不占用左/右操作区；八向、长按连发、按键反馈、Ctrl/Shift 和存档槽等既有能力保持不变。

## v7.3 保留游戏信息区的统一自由 HUD

v7.3 在 v7.2 的八向移动、长按连发、按键反馈、大键盘和可靠存档槽基础上重做 HUD 分区。游戏终端仍为操作区保留真实宽度，最右侧游戏信息不会被栏位覆盖；但操作区不再拥有一套不可修改的固定按钮，所有按钮共享同一个全屏坐标空间，可跨越分界自由摆放。构建器仍以 `latest` 跟进上游最新正式 Release，不绑定某一个游戏版本。

本版主要变化：

- 游戏终端与操作区继续物理分栏，栏宽不覆盖终端内容；操作区可停靠左/右侧并调节宽度、背景颜色、形状和透明度；
- 原固定九宫格、菜单、大键盘、显示、图形、声音和大图块控制全部迁入统一 HUD，可自由拖到游戏区或操作区；
- 布局模式中拖操作区主体可换边，拖黄色分界可调整宽度；按钮仍可独立修改文字、动作、宽高、形状、颜色、透明度和长按连发；
- 正常游玩时移除书名、HUD 数量和“可拖拽”等常驻说明；仅在首次使用或进入布局模式时短暂提示，并提供临时编辑工具条；
- 大键盘继续只覆盖游戏终端，HUD 不再被整体隐藏，因此操作区和未重叠的自由按键仍可交互；
- Android 返回键成为不会被自定义布局删除的安全菜单入口；v7.2 用户的原固定栏控制会一次性迁移为可编辑 HUD 按键；
- 八向 `7/8/9/4/5/6/1/2/3`、长按连发、按下变色/缩放/震动、Ctrl/Shift 直通大键盘等 v7.2 输入能力全部保留；
- 存档槽不再在同一进程里二次进入不可重入的上游 C 核心。切换时先通过原版 Ctrl+X 完整保存，再由干净 Android 进程以目标 `-u存档槽` 重启；已知槽位与一次性新游戏标志均持久保存；
- Debug 构建继续使用工程内固定的本地更新证书，v7.x 可直接覆盖安装并保留存档；Release 仍应使用发布者自己长期保管的正式密钥。

本次交付时 `latest` 解析并验证的上游正式版是 `v1.3.0.6`；这只是当前构建结果，不是工具中写死的游戏版本。

如果手机里安装的是此前提供的 v6 测试包，请不要先卸载，以免删除应用内部存档。先在游戏里用 Ctrl+X 保存退出、准备工程内 ADB，再双击 `升级安装v7并保留存档.bat`；它会自动选择 `dist` 中最新的 v7 通用 Debug APK，先把存档/用户数据备份到电脑，确认成功后才换装并恢复。

## v6.0 运行时稳定基线（历史说明）

v6.0 已使用 Android 36、NDK r29、CMake 3.22.1 和 Gradle 9.4.1 完成真实全量构建；`arm64-v8a`、`armeabi-v7a`、`x86_64` 三种 ABI 均进入同一 APK。APK 已通过签名、4/16KB ZIP 对齐、Manifest、ELF 架构和动态依赖检查。

本版在 v5.0 启动空帧缓冲修复之上继续解决安装后运行稳定性：

- 每个 Activity 使用唯一 native 会话令牌，旧页面的延迟线程不能重复启动或误停新核心；
- Activity 销毁会唤醒阻塞的 `TERM_XTRA_EVENT`，已有角色时注入 RFB 自身的 `Ctrl-X` 保存退出；
- 修正 API 30–35 在 DecorView 创建前访问 `PhoneWindow.getInsetsController()` 的首屏空指针；沉浸式模式改为 UI 建立后从真实 DecorView 获取控制器；
- native 拒绝同进程双核心运行，帧缓冲分配失败会作为 Java 异常显示，而不是空指针崩溃；
- 帧头中的图形/bigtile 状态改为锁保护快照，消除 UI/core 跨线程数据竞争；
- 资源安装标记由源码标签升级为内容 SHA-256 指纹，同一 RFB 标签下资源发生变化也会可靠重装，同时继续保留存档目录；
- 资源 ZIP 固定时间戳与权限元数据，相同输入可重复生成相同字节；
- 隔离 RFB 的 `format(...)` 宏与 libcurl 的格式属性，并修正 timeout 参数类型，恢复编译器格式检查；
- 增加自适应启动图标、诊断日志轮换，并清理 Gradle 10 弃用写法。
- 外部应用目录未挂载或不可写时，启动诊断自动回退到内部 files 目录。

## v5.0 启动闪退修复与 Android 进程安全退出

v5.0 根据真实安装后的“一点图标立即闪退”现象审计启动链。旧版 `MainActivity` 在 `g_cells` 原生帧缓冲分配前就启动 16ms JNI 轮询，`nativeFrameIntCount()` 却报告完整尺寸，随后 `nativeCopyFrame()` 解引用空 `g_cells`，形成确定性的 native SIGSEGV。v5.0 改为先 `nativePrepare()` 分配帧缓冲，再启动 UI 轮询，同时在 native 帧读取 API 内增加空缓冲与并发锁保护。

此外，RFB 原版 `quit()` 最终调用 `exit()`，不适合作为 JNI 库嵌入 Android 应用。v5.0 增加 Android 专用 core runner，通过 quit hook + `setjmp/longjmp` 将核心退出安全地回传 Java，而不是杀死整个 App 进程；Java 侧增加启动阶段日志和未捕获异常落盘。

若真机仍出现运行时问题，可连接开启 USB 调试的手机，双击 `抓取手机崩溃日志.bat`；脚本使用工程内 portable ADB 自动启动应用，并把 logcat 与应用启动阶段日志保存到 `portable_workspace/logs/`。

这是为 **RoguelikeFansBand 中文版**单独设计的 Android 移植工程。它不依赖、复制或嵌入 angbandroid，也不把 RFB 当作 FrogComposband 插件加载。

核心结构：

```text
Android Activity / RfbTermView / Direct Keyboard / SoundPool
              │ JNI
              ▼
        librfb_android.so
        ├─ RFB 原生 C 核心
        ├─ 专用 Android Term 后端
        ├─ JNI 输入/终端/声音队列桥
        └─ 静态 libcurl + Mbed TLS
```

专用前端已接入 RFB Unicode 码点与双格宽字符、逐格 RGB、8×8/16×16 图块、地形与角色双层绘制、bigtile、`sound.cfg`/SoundPool、libcurl + Mbed TLS + CA bundle、Android IME UTF-8 输入，以及 Windows 特殊键 macro trigger 兼容层。


## v4.9 补丁器回归修复与 ABI 警告审计

v4.9 根据真实 NDK 构建日志修复 v4.8 的一个补丁器缺陷：`dungeon.c` 中另一个已经正确加括号的 `NONLIVING` 判断会让旧脚本误以为目标补丁已经应用，从而跳过真正的过敏事件条件。新版本改为匹配完整条件、写入后再次校验，并在进入 Gradle 前执行已知高风险模式审计。

同时新增：

- `cmd4.c` 的 `???` 文本改用相邻 C 字符串字面量拼接，从源码层彻底避免 `??<` trigraph；
- `wizard1.c` 的三个 `vec_cmp_f` 不兼容函数指针强转改为类型正确的适配器；
- `cast-function-type-mismatch` 与 `trigraphs` 升级为编译错误，防止以后同类问题悄悄进入 APK；
- 新增补丁器回归测试，专门复现“同文件存在另一个正确表达式导致错误跳过补丁”的 v4.8 故障。

详细策略仍见 `docs/WARNING_AUDIT.md`。


## v4.8 警告审计与语义修复

v4.8 不再把 NDK warning 一概视为无影响：

- 精确修复 `personality.c` 中永远为假的等级范围保护条件；
- 精确修复 `dungeon.c` 中逻辑非与位标志判断的优先级错误；
- 将格式安全、隐式函数声明、返回类型、指针/整数转换、未初始化、数组越界、逻辑非优先级、矛盾范围判断等高风险警告提升为编译错误；
- `misleading-indentation` 与未使用变量继续显示，不静默关闭，也不在缺少行为规格时擅自重写游戏逻辑；
- 源码准备阶段生成 `generated/warning-audit.txt`，详细说明本次审计策略。

详细说明：`docs/WARNING_AUDIT.md`。

## v4.7 原生 C/NDK 兼容修复

v4.7 根据真实 NDK r29 构建日志修复首批原生编译阻塞：

- 为 Android/Bionic 强制加入 POSIX 文件描述符声明，并将 RFB 非 SET_UID 路径中的 `_read`/`_write` 映射到 `read`/`write`；
- 修正 `corny.c` 中被 `-Werror=format-security` 拒绝的嵌套格式字符串调用；
- 关闭 libcurl 示例程序构建，避免 APK 工程把数百个 `curl-example-*` 目标一并交给 Ninja；
- 禁用 C trigraph 转换，避免中文字符串中的 `???<` 被编译器改写。

这些改动只解决 Android 平台适配和构建行为，不擅自修改日志里出现的上游游戏逻辑警告。

## v4.7 修复

- 修复 `RfbTermView.java` 中错误的 `new Paint(Paint.Style.FILL)` 构造调用；Android `Paint` 不存在接收 `Paint.Style` 的构造器，改为无参构造后调用 `setStyle(Paint.Style.FILL)`。
- `prepare_source.py` 的 tar 解压显式使用 `filter="data"`，消除 Python 3.14 相关弃用警告，避免 GUI 将普通警告标成 `ERROR:`。

## v4.5 修复

- 修复 `JAVA_TOOL_OPTIONS` 导致 Windows PowerShell 5.1 把 `java -version` 的正常 stderr 输出当作 `NativeCommandError` 的问题。
- Java 版本探测改用 `System.Diagnostics.Process` 独立捕获 stdout/stderr，不再触发 PowerShell 原生命令错误流。
- 便携包装脚本现在显式 `catch` 异常并返回非零退出码。
- GUI 只有在本次运行确实生成新的 `dist/*.apk` 时才显示“构建完成”，防止再次出现 dist 为空却误报成功。


## Windows：真正的便携式图形构建器

当前图形界面标题应显示 **v7.5 Portable**。如果日志一开始直接出现“开始构建 RoguelikeFansBand Android APK”而没有任何“便携环境”下载/准备步骤，说明启动的是旧版目录。


直接双击：

```text
打开图形化构建器.bat
```

不要求电脑提前安装 Python、JDK、Git、Android Studio、Android SDK、NDK 或 CMake。GUI 使用 Windows 自带 PowerShell/WinForms 启动；点击“开始构建 APK”后，缺少前置会自动下载到工程内部：

```text
portable_workspace/
```

主要隔离位置：

```text
portable_workspace/
├─ runtime/python/
├─ runtime/jdk/
├─ runtime/android-sdk/
├─ runtime/gradle-9.4.1/
├─ downloads/
├─ cache/
├─ gradle-home/
├─ android-user-home/
├─ home/
└─ temp/
```

构建器只给自己启动的子进程设置环境变量，不写系统 PATH、不改注册表、不要求管理员权限。RFB 默认解析并下载最新正式 Release 的源码归档，不需要 Git；也可以固定标签、离线复用缓存或使用本地源码。

工程可移动。脚本根据自身位置重新计算根目录；Windows native 构建阶段会临时建立指向当前工程目录的短盘符别名，以降低中文路径、空格和长路径对 NDK/CMake 的影响，结束或 GUI 取消时清理别名。实际文件始终留在工程目录中。

详细说明：`docs/GUI_BUILDER.md`。

## 命令行构建

Windows Debug：

```bat
build_debug.bat
```

Windows Release（未签名）：

```bat
build_release.bat
```

这两个入口同样先准备工程内部的便携环境，不依赖系统 JDK/SDK。

已有本地 RFB 源码时：

```bat
build_debug.bat -SourceDir "D:\path\to\RoguelikeFansBand-zh-CN"
```

## 构建链

```text
WinForms GUI / BAT
        ↓
tools/portable_bootstrap.ps1
        ├─ 便携 Python
        ├─ Temurin JDK 21
        ├─ Android Command-line Tools
        └─ sdkmanager → Android 36 / Build Tools / NDK / CMake
        ↓
tools/portable_build.ps1
        ├─ 进程级隔离环境变量
        ├─ 临时短路径盘符
        └─ embedded Python → tools/build.py
                           ↓
                   tools/prepare_source.py
                           ├─ RFB 源码归档或本地源码副本
                           ├─ curl / Mbed TLS / CA bundle
                           ├─ Android 专用 main
                           ├─ CMake 源码清单
                           └─ 原始字节资源 ZIP
                           ↓
                        Gradle
                           ↓
                    CMake / NDK
                           ↓
                       dist/*.apk
```

## Unicode、RGB 与图块

RFB 的中文化已经进入终端核心，因此 Android 后端直接读取 RFB 自己的 Unicode/RGB/tile 格子状态。JNI 帧 v2 每格保存 Unicode 码点、前景/背景/边框 RGB、宽字符状态，以及角色/物件和地形图块的 row/col。Java 端按“背景 → 地形 → 角色/物件 → Unicode fallback → 边框 → 光标”绘制。

## 声音

`TERM_XTRA_SOUND(event)` 经 native 环形队列进入 Java，`RfbAudioManager` 解析 RFB 的 `lib/xtra/sound/sound.cfg`，使用 `SoundPool` 播放已加载完成的候选样本；`TERM_XTRA_NOISE` 使用 `ToneGenerator`。

## 网络

RFB 原来的 `http.c` 继续调用 libcurl；Android native 构建静态链接 Mbed TLS。GET/POST 两条 curl 路径统一配置 `CURLOPT_CAINFO`，CA bundle 安装在 RFB 运行资料目录。网络并未改写成另一套 Java HTTP API。

## 资源与存档

`prepare_source.py` 按原始字节打包 RFB `lib/` 静态资料，不做文本解码/转码。`save/`、`user/`、`apex/`、`bone/`、`data/` 作为可写目录保留，资源更新不会覆盖存档。
