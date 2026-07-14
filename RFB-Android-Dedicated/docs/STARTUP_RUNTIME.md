# Android 启动与退出运行时

## 启动顺序

1. Activity 建立 UI，但不启动 native 帧轮询。
2. 后台线程安装/更新 `rfb-data.zip`。
3. `RfbNative.nativePrepare()` 分配并清空 native framebuffer，同时返回唯一会话令牌。
4. UI 加载 tileset / sound 配置后启动帧轮询和声音轮询。
5. 后台线程携带同一令牌进入 `nativeStart()`，由 Android core runner 调用 RFB 主循环；过期令牌和第二个并发核心会被拒绝。

沉浸式 system bars 控制在 `setContentView()` 之后执行，并从真实 `DecorView` 获取 `WindowInsetsController`。这避免 API 30–35 的 `PhoneWindow.getInsetsController()` 在首帧创建前解引用空 DecorView。

## 为什么旧版会闪退

旧版在第 1 步就开始轮询，而 native framebuffer 直到第 5 步才分配。`nativeCopyFrame()` 在空指针状态下复制 80×27 cells，造成 native SIGSEGV。

## 进程退出隔离

RFB 是传统桌面程序，`quit()` 会调用 `exit()`。在 JNI 中直接执行会终止 Android 应用进程。Android backend 现在安装专用 quit hook，用 `setjmp/longjmp` 回到 native runner，并把退出码与消息返回 Java。

## Activity 销毁与存档

`onDestroy()` 使用当前会话令牌请求 native 停止并广播条件变量，因此阻塞在 `TERM_XTRA_EVENT` 的核心会被立即唤醒。角色已经建立时，后端在游戏线程中注入 RFB 原生的 `Ctrl-X` 保存退出命令；尚未建立角色时直接通过 Android quit hook 安全退回 Java。旧 Activity 的令牌不能停止新 Activity 的核心。

native runner 同一时间只允许一个核心进入，Activity 的后台任务在资源安装、native prepare、前端初始化三个边界都会检查销毁/中断状态。

## 为什么存档切换要重启进程

会话令牌可以防止“同时”运行两个核心，但不能把上游 RFB 数百个 C 全局/static 对象重置成可二次进入状态。旧版在 `rfb_core_main()` 返回后立即在同一进程启动下一存档，因此槽位参数虽然正确，但核心旧状态仍会让切换失效。

v7.2 在 Ctrl-X 完整返回后同步写入目标槽位，通过系统 `PendingIntent` 预约重新启动，然后结束旧进程。新进程只进入一次核心，并使用 JNI 构造的 `-u<slot>`；“重新开局”的 `-n` 作为一次性标志消费，不会在以后每次启动时重复创建角色。

## 诊断日志

启动阶段和 Java 异常优先写入应用专属 external files 目录的 `rfb-startup.log`。若外部目录尚未挂载、无法创建或不可写，会自动回退到内部 files 目录；应用内部启动失败时会显示实际日志路径。日志超过 1 MiB 时保留一份 previous 后轮换。
