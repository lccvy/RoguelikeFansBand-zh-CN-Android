# RoguelikeFansBand 专用 Android 移植架构

## 1. 设计原则

1. RFB 是唯一游戏核心，不设计多变体插件系统，也没有 loader/dlopen 插件层。
2. RFB 自己的 Unicode、宽字符、RGB 和图块状态是真值源；Android 不重新猜文本编码或字符宽度。
3. 游戏主循环继续同步运行，但放在独立 Java executor 线程中。
4. Android UI 保持事件驱动，输入通过原生条件变量队列适配到 `TERM_XTRA_EVENT`。
5. C/Java 边界传结构化终端格，不传屏幕截图，也不把 `char *` 当成不明编码文本。
6. 网络沿用 RFB `http.c` 与 libcurl 接口，不在 Java 侧建立第二套业务 HTTP 实现。
7. 平台特有能力各自走窄桥：帧快照、声音事件队列、特殊键 trigger、运行时图形/声音开关。

## 2. 原生生命周期

```text
MainActivity.startGame()
  → AssetInstaller.install()
  → nativePrepare() 分配帧缓冲/取得会话令牌
  → RfbTermView.configureAssets()
  → RfbAudioManager.start()
  → RfbNative.nativeStart(session token)
  → runner 校验令牌/单核心状态
  → 设置 RFB_CURL_CA_BUNDLE
  → rfb_core_main()
  → main.c 参数解析
  → -mandroid 选择 init_android()
  → term_init()
  → Term_activate()
  → init_angband()
  → play_game()
```

`prepare_source.py` 不修改上游 `main.c` 本体，而是每次根据解析出的源码版本生成 `generated/rfb_main_android.c`：改名入口函数，并插入 Android frontend 选择块。默认版本是上游最新正式 Release；上游结构发生变化时脚本拒绝盲目打补丁。

## 3. 帧协议 v2

JNI `nativeCopyFrame(int[])` 使用整数数组。

### 3.1 Header：10 个 int

```text
header[0] cols
header[1] rows
header[2] cursor x
header[3] cursor y
header[4] cursor visible
header[5] big cursor
header[6] generation
header[7] graphics mode
header[8] bigtile enabled
header[9] layout version = 2
```

### 3.2 每格：11 个 int

```text
cell[0]  Unicode code point
cell[1]  foreground RGB 0xRRGGBB
cell[2]  background RGB 0xRRGGBB
cell[3]  border RGB 0xRRGGBB
cell[4]  border flags
cell[5]  legacy byte
cell[6]  tile flags
cell[7]  character/object tile row
cell[8]  character/object tile col
cell[9]  terrain tile row
cell[10] terrain tile col
```

完整 Unicode 码点来自 RFB `Term->old->uc`。`TERM_UC_WIDE_TRAIL` 原样传递；Java 判断前一格是否为宽字符时，依据下一格是否为这个明确尾格标志，而不是调用 Android 的字体测量结果推断宽度。

## 4. 图块模式

native `Term_pict_android` 同时保存角色/物件图块与地形图块索引。行列索引按低 7 位解析。

Java 渲染顺序：

```text
所有格子背景 RGB
        ↓
图块层
  Original: 8x8.png 单层
  Adam Bolt: 16x16.png 地形层 → 角色/物件层
        ↓
没有有效图块的 Unicode 字形
        ↓
RGB 边框
        ↓
光标
```

bigtile 只改变目标矩形宽度，不改变源 atlas 索引。源矩形必须通过 64 位边界检查；非法行列值退回字形路径。

## 5. 声音链路

```text
TERM_XTRA_SOUND(event id)
        ↓
native ring queue（容量 256）
        ↓
JNI nativeDrainSoundEvents()
        ↓
RfbAudioManager
        ↓
sound.cfg [Sound] 事件映射
        ↓
SoundPool 异步加载/播放
```

每个事件最多 8 个样本。Java 只从已经完成 SoundPool 加载的样本中随机选择，避免异步加载阶段出现“随机抽中未加载 sample id → 整个事件无声”。

`TERM_XTRA_NOISE` 以事件 `-1` 入队，由 Java 使用 `ToneGenerator` 产生终端蜂鸣。

## 6. 网络与 TLS

```text
RFB http.c
  → libcurl static
  → Mbed TLS static
  → Android/Bionic socket
```

CMake 只保留 HTTP/HTTPS 所需的 curl 功能，关闭 CLI、测试、文档、SSH、QUIC、HTTP/2 与额外压缩库依赖。

`prepare_source.py` 对 RFB `http.c` 两个 curl 初始化分支做结构验证后补丁：

```text
GET curl handle  → _rfb_android_apply_ca_bundle(curl)
POST curl handle → _rfb_android_apply_ca_bundle(curl)
```

辅助函数读取：

```text
RFB_CURL_CA_BUNDLE
```

并设置：

```text
CURLOPT_CAINFO
```

JNI 启动前将环境变量指向：

```text
<filesDir>/rfb/lib/xtra/curl/cacert.pem
```

证书束由准备脚本下载并验证基本结构后纳入资源 ZIP。

## 7. 特殊键 trigger 兼容

普通 Unicode 文本保持 UTF-8 字节流。只有 RFB Windows 前端视为特殊键的 Android 等价按键，才生成：

```text
0x1F [C][S][A] x [K] HH 0x0D
```

`RfbKeyEncoder` 使用 PC set-1 扫描码表，覆盖导航、功能键、锁定键、系统键以及小键盘数字/运算符。小键盘来源加 `K`。

Enter 与 NUMPAD_ENTER 继续送 CR，不放进 trigger，因为 RFB Windows 的 `special_key_list` 并未将 Return 列为特殊键。Caps Lock 则明确在 special list 中，因此应生成扫描码 trigger，而不是仅仅作为修饰状态吞掉。

屏幕上的虚拟方向键不经过这个 Windows 兼容层。RFB 本身是八向 roguelike，因此 HUD 和大键盘直接发送原生数字小键盘命令 `789456123`，中心 `5` 表示原地等待。

## 8. 为什么保留 legacy byte

RFB Windows 显示层对 byte 127 有特殊墙体图案语义。专用 Android 帧同时传 `legacyByte`，因此 Java 可以在完整 Unicode 码点之外保留这种历史显示约定。当前实现使用程序绘制的砖墙纹理，不依赖 Windows bitmap/GDI。

## 9. 路径策略

JNI 构造：

```text
-dedit=<root>/lib/edit
-dpref=<root>/lib/pref
-dfile=<root>/lib/file
-dhelp=<root>/lib/help
-dinfo=<root>/lib/info
-dxtra=<root>/lib/xtra
-duser=<root>/lib/user
-dsave=<root>/lib/save
-dapex=<root>/lib/apex
-dbone=<root>/lib/bone
-ddata=<root>/lib/data
-dscript=<root>/lib/script
-mandroid
```

因此游戏数据访问仍经过 RFB 原有路径全局变量，Android 层只决定目录位置。

## 10. 触控层与长按

`TouchFeedbackButton` 统一实现 pressed 颜色、缩放动画和触感反馈。`PressRepeater` 在不破坏普通 `performClick()`/辅助功能的前提下提供 300ms 起始延迟、82ms 间隔的按住连发。

`GameKeyboardDialog` 保留历史类名，但已不继承 Android `Dialog`。它把实际键盘 View 加入 `termContainer`，因此只覆盖游戏终端，不占用保留操作区；统一 HUD 位于它上层，未重叠的按钮仍可同时交互。

v7.3 的内容层由 `termContainer + panelSpacer` 水平组成。`panelSpacer` 为操作区保留真实宽度，保证终端最右侧信息不会画到栏位下面；它可左右换边且至少为终端保留 240dp。`CustomHudOverlay` 则覆盖整个根窗口，操作区背景和所有按钮共享同一坐标空间。按钮不是操作区的子控件，因此能跨边界自由摆放。

游玩模式下 HUD 自身和操作区空白不消费触摸，只有具体按键消费；布局模式才接管背景触摸。操作区主体可拖过屏幕中线切换停靠边，黄色内边界可改变宽度。按键位置以可用移动距离的 0–1 归一化坐标保存；操作区样式、栏宽和停靠方向单独持久化。编辑工具条只在布局模式临时出现，Android 返回键提供不可删除的恢复入口。

v7.4 的按键编辑器把当前归一化位置换算为以 HUD 左上角为原点的 dp 坐标，用户可输入 X/Y 精确对齐；保存时再按当前屏幕和按钮尺寸换回归一化值，因此不需要迁移旧布局数据。大键盘显示期间，HUD 以按钮中心点判断所属区域：游戏区按钮设为不可见且禁用，保留操作区按钮保持显示和交互；键盘关闭后统一恢复。

v7.5 不再使用同时设置 `AlertDialog.setMessage()` 与 `setItems()` 的存档选择器，因为部分系统只渲染消息区域。存档目录改为独立 `ScrollView`，每个槽位拥有明确的打开/新游戏操作。核心返回码 `0` 被视为健康退出：UI 不显示诊断路径，并删除当前及上一份启动跟踪；异常返回和 Java 故障才保留日志。日志在每次写入前轮换，当前与上一份各限制为 256KB。

## 11. 存档槽与干净进程重启

上游 RFB 核心包含大量进程级 static/global 状态，`rfb_core_main()` 返回后在同一进程再次调用不可靠。v7.2 的切换流程是：

```text
选择目标槽位
  → nativeRequestStop(session token)
  → Term 在游戏线程注入 Ctrl-X
  → 原版保存并退出 rfb_core_main()
  → SharedPreferences 同步写入目标槽位/一次性 -n
  → AlarmManager 预约 MainActivity
  → 结束当前 Activity 并终止旧进程
  → 新进程以 -u<slot> [ -n ] 进入核心
```

这个机制不改变上游存档字节，只确保每次进入核心时都是干净的 C 全局状态。

## 12. 运行时切换

Java 可通过 JNI 请求：

```text
graphics mode: none / original / Adam Bolt
sound enabled: true / false
bigtile enabled: true / false
```

请求先进入 native pending state，再由游戏线程在安全位置应用，避免 UI 线程直接修改 RFB 全局图形状态。

## 13. 验证边界

本工程已用 Android 36 / NDK r29 / CMake 3.22.1 真实编译全部 198 个 RFB 核心单元、curl、Mbed TLS 和三个 ABI，并完成 APK 签名、对齐、Manifest、ELF/动态依赖检查。Android 35 AOSP 模拟器已完成安装、Activity 前台、资源安装、JNI/Term 初始化和核心就绪后 180 秒稳定性测试。以下仍需要实际手机上的交互验收：

- 图块 PNG 在目标设备上的实际透明叠加效果；
- SoundPool 在不同厂商设备上的并发/延迟表现；
- RFB 网络服务端的真实 HTTPS 交互；
- 蓝牙键盘、USB 键盘与厂商键盘事件码差异。

这些是运行验证边界，不再是源码中的空实现或 TODO。
