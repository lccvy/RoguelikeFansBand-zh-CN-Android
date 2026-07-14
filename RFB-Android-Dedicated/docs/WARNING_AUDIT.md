# RFB Android 原生警告审计策略

本工程不把所有 warning 当成“无影响”，也不为了构建通过而全局关闭警告。

## 已确认会影响语义并自动修复

### personality.c：无效等级边界保护

上游条件：

```c
if ((p_ptr->lev < 1) && (p_ptr->lev > 50)) return;
```

同一个等级不可能同时 `< 1` 且 `> 50`，因此保护条件永远为假；Android 源码准备阶段精确改为 `||`。补丁要求完整目标模式恰好出现一次，否则停止构建。

### dungeon.c：位标志判断优先级

过敏事件条件中的上游表达式：

```c
!get_race()->flags & RACE_IS_NONLIVING
```

C 运算符优先级会先计算 `!flags`，再与标志位做 `&`。Android 源码准备阶段把**完整的过敏事件条件**精确改为：

```c
!(get_race()->flags & RACE_IS_NONLIVING)
```

v4.8 曾以短字符串片段判断“是否已修复”；当同一个 `dungeon.c` 中存在其他已经正确加括号的 NONLIVING 判断时，会错误跳过真正目标。v4.9 改为完整条件匹配、目标计数和写入后复核，并在 Gradle 前再次扫描已知危险模式。

### cmd4.c：三字符组转换

翻译字符串中的 `???<` 包含历史 C trigraph 序列 `??<`。仅依赖编译开关仍可能因工具链行为而出现警告，因此 v4.9 在源码准备阶段把字符串拆成相邻字面量：

```c
"<color:y>??" "?</color>"
```

运行时文本仍是 `???`，源码中不再形成 `??<` token。`trigraphs` 同时升级为 error，防止新的同类文本悄悄通过。

### wizard1.c：整数向量比较器 ABI 适配

`c-vec` 的整数元素按 `(void *)(intptr_t)value` 保存，而排序比较器类型是：

```c
typedef int (*vec_cmp_f)(const void *, const void *);
```

原代码把 `int (*)(int, int)` 直接强转为 `vec_cmp_f` 后调用。在 64 位 ABI 上，这种不兼容函数指针调用属于未定义行为风险。v4.9 增加类型正确的适配器：先把 `const void *` 显式还原为 `intptr_t`/`int`，再调用原比较函数；三个排序调用点不再使用函数指针强转。

## 保留为 warning、但不盲目改变语义

### misleading-indentation

这类警告说明源码缩进容易让人误会控制流，但 C 的真实控制流由大括号和语句边界决定。日志中的部分位置是连续的独立 `if` 检查；在没有行为规格证明的情况下，自动加大括号或改成嵌套条件反而可能制造玩法 bug。

因此它们继续可见，不使用 `-Wno-misleading-indentation` 隐藏，也不自动改控制流。

### unused / unused-but-set

未使用变量本身不会自动改变运行时行为，但可能提示死代码、遗留逻辑或未完成重构。本工程不因 Android 移植擅自删除变量，警告继续保留以供后续代码审计。

### parentheses-equality

`if ((a == b))` 的额外括号不改变 C 表达式语义。它继续作为样式警告显示，不自动把 `==` 改成 `=`，也不为追求“零 warning”修改行为。

## 提升为编译错误的高风险类别

RFB Android native target 将以下诊断提升为 error：

- format-security
- implicit-function-declaration
- return-type
- incompatible-pointer-types
- int-conversion
- uninitialized
- array-bounds
- logical-not-parentheses
- tautological-overlap-compare
- cast-function-type-mismatch
- trigraphs

策略是：容易导致未定义行为、越界、错误 ABI、平台差异或明显逻辑错误的警告，阻止 APK 生成；纯样式和需要人工判断意图的警告，保留显示但不自动重写游戏逻辑。

## 构建前二次审计

`prepare_source.py` 在补丁完成后再次检查：

- personality.c 的不可能等级条件不得残留；
- dungeon.c 的错误 NONLIVING 优先级模式不得残留；
- cmd4.c 的原始 `???<` 三字符组源序列不得残留；
- wizard1.c 的不兼容 `vec_cmp_f` 强转调用不得残留；
- 类型正确的比较器适配器必须存在。

任何一项失败，源码准备阶段直接退出，不进入 Gradle/NDK 构建。

## 边界

警告审计、严格编译和源码静态检查能显著降低“能编译但行为错误”的风险，但不能替代真机运行测试。最终可玩性仍需覆盖：新建角色、读写存档、中文输入、图块、声音、网络、主要职业/种族创建、地图切换、死亡/结算等运行路径。
