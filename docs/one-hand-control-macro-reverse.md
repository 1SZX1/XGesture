# One Hand Control 手势宏（macro）完整逆向

> 目标：把「One Hand Control V1.2.6 MOD」的手势宏实现逆到**可像素级移植到 XGesture** 的深度。
>
> 样本：`D:\Downloads\Quick Share\One Hand Control MOD APK V1.2.6 (Pro Unlocked).apk`
> sha256 `F6F2466DE0CE69AA7134AD5A40BA46E7E7EF6373225F5E434277C1DB03C9B823`
>
> 反编译产物：`.ohc_decompiled/`（jadx）、`.ohc_apk/`（解包）
> 工具：`tools/dex_method_dump.py`（指令级 dump）、`.tmp/dex_str.py` + `.tmp/find_dex_str.py`（严格 MUTF-8 字符串表）、`.tmp/strict_dump.py`（用严格串表的指令 dump）

---

## 0. 结论速览

| 维度 | 结论 |
|---|---|
| 存储 | SharedPreferences 键 **`macros_v2_json`**，值是 **JSON 数组**字符串，默认 `"[]"` |
| 宏对象 | `schemaVersion:2` + `id` / `name` / `targetPackage` / `createdAtMs` / `updatedAtMs` / `steps[]` |
| 步骤类型 | **只有 3 种**：`delay`（等待）/ `tap`（点击）/ `swipe`（滑动，可带中间点） |
| 执行前提 | 每条宏**绑定一个目标 App**；执行时先拉起该 App，并等它到前台（上限 4.5s） |
| 模拟输入 | 无障碍 `dispatchGesture`（`GestureDescription`），**不需要 root / Shizuku / 悬浮窗权限** |
| 无 | 没有条件、循环、变量、分支、多目标、按键/文本输入/长按/多指；步骤是**线性顺序执行** |
| 只有一个执行入口 | 任意触发源都走 `EdgeGestureService.a()` → `y2.i.C(macroId)`；门控全局统一 |
| 触发源 | 边缘手势槽位（左右下 × z1/z2 × 约 32 种）、多角度自定义拉出、快速访问/快捷工具/应用启动器面板项、录制时顺手绑定；**无**摇一摇/磁贴/小部件/外部 Intent |
| 绑定模型 | 动作字符串 **`run_macro:<uuid>`** 引用宏 id；宏内没有反向触发列表；删宏只清面板项，**手势键留悬空**（读时退化成 `none`） |
| 创建方式 | **只能录制**（无步骤编辑器、无导入导出）；id = `UUID.randomUUID()` |
| Pro 门控 | "Macros" 是 Pro 特性；录制与触发都会被挡 |
| 单飞 | 进程级静态锁：任一宏在跑，其它宏一律被拒（不比较 id） |
| 可中断性 | **没有任何取消入口**；用户碰屏幕不打断，只有"下一步开始前"发现前台变了才中止 |

---

## 1. 分析陷阱（先说，避免后来者踩）

1. **指令长度表写错会导致"凭空多出字面量"（我自己踩过，已修）**：
   本仓库 `tools/dex_method_dump.py` 原先把 `0xB0–0xCF`（binop，23x 格式）当成 **3 unit**，正确值是 **2 unit**。
   后果：只要方法里出现 binop，其后所有指令**整体错位**，dump 出来的 `const-string` 会被归到错误的指令上，看起来像"每个真实键名前面都紧挨一个乱序孪生串"。
   **已修正（2026-10-08）**，并在代码里留了注释。修好后重跑 `A2/z.f0`，绝大多数幻影字面量消失。
2. **确实存在"乱序孪生串"（诱饵），而且是系统性的**：
   字符串池里各有一处 `pety`(↔`type`)、`tessp`(↔`steps`)、`pta`(↔`tap`)、`wspeo`(↔`swipe`)、`upnosi`(↔`points`)、`tdsAertsceM`(↔`createdAtMs`)、`ekPmrtggaeatc`(↔`targetPackage`)、`c_sovbnos_majr`(↔`macros_v2_json`)。
   指令级确认的**真代码里的死诱饵**（同一个寄存器、立刻被真串覆盖）：
   - `A2/z;->f0`：`const-string v1,'pta'` → `const-string v1,'tap'`；`'dpsMol'` → `'holdMs'`
   - `A2/N;->o` @insn+459/461：`const-string v14,'c_sovbnos_majr'` → `const-string v14,'macros_v2_json'`
   - `A2/N;->o` @insn+770/772：`const-string 'm:cmar_oru'` → `const-string 'run_macro:'`
   **结论不变：判键名不要靠 grep dex 字符串，以 jadx 反编译 + 写入端代码为准。**
3. **jadx 在 `when(type)` 分支上有失真**：它会在每个分支末尾插一句 `obj = null`，看起来像"所有步骤都被丢弃"。
   指令级核验（`A2/z.f0`）证明是假的：每个分支构造完 step 后 `goto` 到公共的 "add" 块，`obj = null` 只存在于**类型不认识**的路径上（偏移 471）。→ **以字节码为准**。
4. 混淆命名：类名是短名，jadx 会重命名成 `C0048q` 这种别名。真实 dex 名见各表的"真实名"列。

---

## 2. 数据模型与 JSON Schema（第一手核实）

### 2.1 持久化

| 项 | 值 | 证据 |
|---|---|---|
| 键 | `macros_v2_json` | `A2/z.java:1393`（读）、`:1648`（写）；`A2/N.java:38`（键集合） |
| 默认 | `"[]"` | 同上 |
| 解析 | `org.json`（`JSONArray` / `JSONObject`） | `A2/z.java:1395` |
| 解析失败 | **整体丢弃，返回空列表** | `A2/z.java:1405-1407`（`catch (Exception) → 空 List`） |
| 写回 | 整个数组 `toString()` 覆盖写 | `A2/z.java:1648` |

### 2.2 宏对象（写入端 `A2/z.s0(ArrayList)`，`A2/z.java:1643`）

```json
{
  "schemaVersion": 2,
  "id": "…",
  "name": "…",
  "targetPackage": "…",
  "createdAtMs": 0,
  "updatedAtMs": 0,
  "steps": []
}
```

读取端（`A2/z.f0(JSONObject)`，`A2/z.java:282-…`）：

| 键 | 必填 | 缺失时行为 | 证据 |
|---|---|---|---|
| `id` | **是**（空/blank → 整条宏**丢弃**） | — | `A2/z.java:295-298` |
| `targetPackage` | **是**（空/blank → 整条宏**丢弃**） | — | `A2/z.java:299-302` |
| `steps` | 否 | `null` → 空数组 | `A2/z.java:303-306` |
| `name` | 否 | 默认 `"Untitled Macro"` | 常量串 `Untitled Macro`（`f0` 内）；写入端用 `f309b` |
| `createdAtMs` / `updatedAtMs` | 否 | 默认 0 | `f0` 内 `optLong` |

### 2.3 步骤（`steps[]`）

类型判别字段：**`type`**（字符串，`equals` 比较，大小写敏感）：`"delay"` / `"tap"` / `"swipe"`；不认识的类型 → 该步骤**被跳过**（不报错）。

| type | 字段 | 类型 | 写入端 | 读取端默认值 | 单位/取值 |
|---|---|---|---|---|---|
| `delay` | `durationMs` | long | `A2/z.java:1611` | **200** | ms（执行时 clamp 0..10000） |
| `tap` | `x`,`y` | float | `:1614` | **0.5 / 0.5** | 屏幕宽/高的**比例** 0~1 |
| `tap` | `durationMs` | long | `:1614` | **80**（旧字段 `holdMs` 作回退） | ms（执行时 clamp 1..2000） |
| `swipe` | `startX`,`startY` | float | `:1621` | **0.5 / 0.8** | 比例 |
| `swipe` | `endX`,`endY` | float | `:1621` | **0.5 / 0.2** | 比例 |
| `swipe` | `durationMs` | long | `:1621` | **250** | ms（执行时 clamp 50..5000） |
| `swipe` | `points[]` | array | `:1624` | 空数组 | 每项 `{x,y}`，默认 **0.5 / 0.5**，比例 |

兼容性细节：
- `tap.durationMs` 读取时是 `optLong("durationMs", optLong("holdMs", 80))` → **兼容 v1 的 `holdMs`**。
- `swipe.points` 的语义见 §3.4：**列表的第 0 个点被跳过**，且当 `points.size() >= 2` 时**不再追加 `endX/endY`**（末点即终点）。

### 2.4 内置预设里没有宏示例

`assets/bundled_presets/starting_pack.json` / `dev_setup.json` 里 `macros_v2_json` 的值都是 `"[]"`（第 601 / 597 行）→ 首次安装/重置后宏列表为空，**没有可参考的内置宏**。

---

## 3. 执行引擎（第一手核实）

主体：`y2.w0.b(A2.c macro)`（jadx 别名 `Y2/w0.java:125-287`）。
`A2.c` 字段：`f308a`=id、`f309b`=name、`f310c`=targetPackage、`f311d`=steps、`f312e`=createdAtMs、`f313f`=updatedAtMs。

### 3.1 流程

1. `prevForeground = null`
2. `launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)`
   - `null` → 遥测 `target_app_not_found` + toast `toast_target_app_not_found` → 结束
     （`Y2/w0.java:284-286`）
3. `intent.addFlags(0x10000000 /* NEW_TASK */)` → `startActivity`
4. **等目标 App 到前台**：每 `100ms` 查一次 `rootInActiveWindow.packageName`，总上限 **4500ms**
   （`Y2/w0.java:145-157`）
   - 超时 → 遥测 `target_app_timeout` + toast `toast_macro_canceled_app_timeout` → 结束（`:158-163`）
5. `Thread.sleep(250)`（`:164`）
6. toast `toast_running_macro(N)` —— **N = 非 delay 步骤数**（即真正会动手势的步数），`O1/o.java:239-253`（遍历计数、跳过 `A2.q`）（`:166`）
7. `launchBudget = 实际等待耗时 + 250`（`:167`）
8. 逐个步骤执行（`:170-276`）：
   1. 取当前前台包；若**非空且 ≠ 目标** → 遥测 `foreground_changed` + toast `toast_macro_stopped_foreground_changed` → 结束（`:172-178`）
   2. 按类型执行（见 §3.2~3.4）
   3. 若返回 `false` → 遥测 `step_failed(stepType=步骤类简名)` + toast `toast_macro_step_failed(步骤类简名)` → 结束（`:259-265`）
   4. 若该步骤**不是** delay → 置"已执行过真实动作"标志（此后 delay 不再抵扣启动耗时）（`:266-275`）
9. 全部走完 → toast `toast_macro_complete(宏名)`（`:277-279`）
10. **异常处理坑**：`startActivity` 到整个循环被一个 `try { … } catch (Exception unused) { }` 包住（`:143`、`:281`）。
    - 步骤类型未知时直接 `throw new RuntimeException()`（`:229`）→ 被这个 catch 吞掉
    - catch 之后代码**继续往下走**，于是会打**误导性的** `target_app_not_found` + "找不到目标应用" toast
    - 即：宏中途崩了，用户看到的是"目标应用不存在"

### 3.2 `delay` 步骤（`A2.q`，字段 `f367a`=ms）

```
if (还没执行过真实动作) {
    effective = max(0, durationMs - launchBudget)   // 抵扣"拉起 App 的耗时"
    launchBudget = max(0, launchBudget - durationMs)
} else {
    effective = durationMs
}
if (effective == 0) 跳过 sleep
else Thread.sleep(clamp(effective, 0, 10000))
```
证据：`Y2/w0.java:179-206`（`j5`=launchBudget、`j6`=durationMs、`j7/j8`=抵扣、`O1.p.l(…,0,10000)`）。
设计意图：宏开头的等待（例如等 App 启动完成）会被**实际启动耗时抵扣**，避免白等；一旦开始点/滑，就按字面时长等待。

### 3.3 `tap` 步骤（`A2.s`，`f374a`=x、`f375b`=y、`f376c`=durationMs）

1. `(px,py) = (clamp(x,0,1)*realWidth, clamp(y,0,1)*realHeight)`（`c()`，用 **getRealMetrics**：`Y2/w0.java:289-304`）
2. 指针浮层移到 `(px,py)`（`U1.a(px,py)`，`F2.U1`）
3. `Path.moveTo(px,py)`（**只有 moveTo、没有 lineTo** → 单点 = 点击）
4. 派发手势，时长 `clamp(durationMs, 1, 2000)`（`:222-225`）

### 3.4 `swipe` 步骤（`A2.r`，`f368a,f369b`=起点、`f370c,f371d`=终点、`f372e`=ms、`f373f`=points）

1. 起点/终点比例 → 像素（`c()`）
2. `dur = clamp(durationMs, 50, 5000)`（`:234`）
3. 指针浮层移到起点；`postDelayed(把浮层移到终点, dur)`（`:235-241`）
4. 路径：
   - `moveTo(起点)`
   - 若 `points.size() < 2` → `lineTo(终点)`（直线）
   - 否则 → 遍历 `K2.n.d0(1, points)`（**从下标 1 开始**，即跳过第一个点）逐个 `lineTo`，**不再追加终点**
   （`:242-255`）
5. 派发手势，时长 `dur`

> 移植注意：`points` 的"第 0 点跳过 + 末点即终点"是**真实行为**，直接照抄才能行为一致；否则滑动轨迹会不同。

`points` 的**真实布局**（由录制端反推并已核实，`F2/C0248n.java`）：
- `points[0]` = **按下点**（`ACTION_DOWN` 时强制写入，`F2/C0248n.java:161`）
- `points[1..n-1]` = 移动过程中的采样点（`ACTION_MOVE`，`:167`）
- `points[last]` = **抬起点**（`ACTION_UP` 时强制追加，`:194`；若整个手势没有 MOVE，列表里就只有按下点，见 `:213` 的兜底 `[起点, 终点]`）
- 执行端 `moveTo(startX,startY)` 已经落在按下点上，所以用 `drop(1)` 跳掉 `points[0]` —— 两边是同一条轨迹，不是巧合

所以「官方录制出来的宏」总是 `startX/startY`=按下点、`endX/endY`=抬起点、`points`=[按下, …采样…, 抬起]；`endX/endY` 只在 `points.size() < 2` 时作为直线兜底被用到。

### 3.5 手势派发与成功判定（`a(Path, durationMs)`，`:109-123`）

- `Build.VERSION.SDK_INT < 24` → 直接 `false`
- `GestureDescription.Builder().addStroke(StrokeDescription(path, 0, durationMs)).build()`
- 主线程 `dispatchGesture(gesture, callback, null)`，回调 `F2.d0`（`F2/C0219d0.java`）：
  - `onCompleted` → `flag = true` + `countDown`
  - `onCancelled` → `flag = false` + `countDown`
- 等待 `durationMs + 1500ms`；超时未回调 → flag 保持初值 `false`
- 返回值 = flag ⇒ **取消或超时都会被判为"步骤失败"**

### 3.6 常量表（照抄用）

| 常量 | 值 | 来源 |
|---|---|---|
| 前台轮询间隔 | 100 ms | `Y2/w0.java:155` |
| 前台等待上限 | 4500 ms | `:147` |
| 启动后 settle | 250 ms | `:164` |
| 启动耗时预算 | 等待耗时 + 250 ms | `:167` |
| delay clamp | 0 … 10000 ms | `:206` |
| tap 时长 clamp | 1 … 2000 ms | `:225` |
| swipe 时长 clamp | 50 … 5000 ms | `:234` |
| 手势等待余量 | duration + 1500 ms | `:121` |
| 坐标基准 | `getRealMetrics`（真实分辨率，含状态栏/导航栏区域） | `:289-303` |

---

### 3.7 触发链、防重入、线程与中止（引擎侧，已指令级核实）

**入口链**

```
y2.T(actionId = "run_macro:<macroId>")          Y2/T.java:14
  └─ EdgeGestureService 手势派发 → C1502i.g(action)
       └─ action 是 y2.T → C1502i.C(macroId)     Y2/C1502i.java:2132-2133 → :424-461
```

`C1502i.C(macroId)` 做的事：
1. 宏不存在 → toast `toast_macro_not_found`（"Macro not found"）→ 返回
2. `Build.VERSION.SDK_INT < 24` → 遥测 `gesture_dispatch_requires_api_24` + toast `toast_macro_requires_android_n` → 返回（**不起线程**）
3. **进程级静态锁**：`synchronized (w0.f13450g) { if (w0.f13451h == null) w0.f13451h = macroId else 拒绝 }`
   - 粒度 = 全进程、**不比较宏 id**：只要有一个宏在跑，任何宏都被拒
   - 拒绝 → 遥测 `macro_already_running` + toast `toast_macro_already_running`
4. 抢到锁 → `new Thread(B2.b(engine, macro, macroId, 9)).start()`（**裸线程**、非 daemon、无优先级设置）
5. Runner 用 `try { w0.b(macro) } finally { 仍是自己占用才清空 f13451h }` → 正常/异常都会解锁（不会永久锁死）

**线程模型**

| 环节 | 线程 |
|---|---|
| `w0.b()` 全体（轮询、sleep、手势等待） | 那条新建的工作线程 |
| `dispatchGesture` | **主线程**（`Handler(mainLooper).post`） |
| 手势结果回调 → `countDown` | 主线程 |
| toast / 手指指示器 | 主线程 post |
| 等待手势结束 | 工作线程阻塞在 `CountDownLatch.await(duration + 1500ms)` |

→ **主线程不会被宏阻塞**（只有一个轻量的 prefs 读取在调用线程上）。

**手指指示器（`F2.U1` / `F2.T1`）**：纯视觉、`NOT_FOCUSABLE | NOT_TOUCHABLE`，直径 `42dp`，加进去 320ms 后自动移除（`U1.java:39,49`）。
- `tap`：先把圆点放到点击点，再派发手势
- `swipe`：圆点放起点，`postDelayed(圆点跳到终点, durationMs)` → **圆点会在手势结束的同时跳到终点**；若 `points` 的末点 ≠ `endX/endY`，会出现"视觉终点 ≠ 实际触摸终点"（`Y2/w0.java:241`）

**中止/打断的现实边界**

- **没有任何"取消/打断"入口**：用户碰屏幕不会打断宏（全局没有 cancel 标志，`f13450g/f13451h` 只用于互斥）
- 切走 App 只在**下一个步骤开始前**被发现 → 若当前步是 10s 的 delay，最多还要等满 10s 才中止
- 中止时**不会**显式清理指示器（每个圆点自己 320ms 后消失）
- 失败除了 toast，还会写诊断：`one_hand_control_diagnostics/last_action_failure_json` + 追加 `action_failed` 到 `recent_events_json`（仅保留最近 80 条）

---

## 4. 宏录制（record macro）

宏**不只是手编**：App 里能录制操作并生成宏。

落盘入口：`EdgeGestureService.java:3516-3568`
- 过滤：**丢弃所有 ≤80ms 的 delay**（`:3531`）；步骤列表为空则不生成宏（`:3535`）
- 名字为空 → `R.string.default_recorded_macro_generic`（`:3542`）
- 目标包名 = 录制会话的 `s3.f2014a`（`:3545`）
- `id = UUID.randomUUID()`，`createdAt/updatedAt = now`，`schemaVersion = 2`（`:3547-3550`）
- 追加到现有宏列表并整体写回 `macros_v2_json`（`:3551-3553`）
- **可选顺手绑定手势**：`s3.f2019f`（`F2.Q`：handleId/side/slot）非空时 → `z.A0(…, new T(uuid, name))`（`:3558-3563`）

录制触摸处理：`F2.n.onTouchEvent`（`F2/C0248n.java:123-261`）
- 全屏捕获 View；**落在录制控制条（`edgeGestureService.J`）范围内的触摸被忽略**（`:139-153`）
- DOWN：记录按下点/时间，清空采样表，把按下点写入采样表，提示 `macro_status_touch_started`（`:156-163`）
- MOVE：采样（`:167`）
- UP（`:178-229`）：
  - 按压时长 `l2 = clamp(now - downTime, 1, 5000)`（`:184`）
  - 距上一次输入的间隔 `l4 = clamp(downTime - 上次, 0, 10000)`；**仅当 `> 80ms`** 才插入 `delay` 步骤（`:189-193`）
  - 位移 `hypot(UP-DOWN)` **≤ 40px → 判定为 `tap`**（用**按下点**坐标，时长 `clamp(l2,30,2000)`）（`:203-205`）
  - 否则 → `swipe`（起点=按下、终点=抬起、时长 `clamp(l2,50,5000)`、`points`=归一化采样表）（`:206-219`）
  - 记录后**立即回放**这个手势给用户看（`A2.S(3,s3)` + `dispatchGesture`；API<24 → 遥测 `gesture_dispatch_requires_api_24`）（`:230-248`）
  - 提示：`macro_status_tap_recorded` / `macro_status_swipe_recorded` / `macro_status_step_recorded`（`:229`）
- 坐标归一化：`edgeGestureService.i0(x, y)` → 0~1 比例

### 4.1 采样规则（`points` 怎么来的）

采样函数：`EdgeGestureService.d(S s, float x, float y, boolean force)`（`EdgeGestureService.java:623-628`）：

```kotlin
fun sample(s: MacroRecordingState, x: Float, y: Float, force: Boolean) {
    val last = s.touchPathPoints.lastOrNull()
    val dist = if (last == null) 0f else hypot(x - last.x, y - last.y)
    if ((force || last == null || dist >= 24.0f) && (last == null || dist >= 1.0f)) {
        s.touchPathPoints.add(Point(x, y))
    }
}
```

| 规则 | 值 | 说明 |
|---|---|---|
| 抽稀阈值 | 距上一采样点 **≥ 24px** | 非强制采样（MOVE）必须超过这个距离才记一点 |
| 强制采样 | DOWN、UP 传 `force=true` | 即使没到 24px 也记，但仍要求与上一点 **≥ 1px**（列表为空时例外） |
| 点数上限 | **无** | 采样表不设上限（对比 XGesture 侧 `MAX_POINTS=200`） |
| 记录时机 | DOWN（`:161`）、MOVE（`:167`）、UP（`:194`） | 都是当时的 `rawX/rawY` |

→ 所以 `points` 是**按位移抽稀的轨迹**：首点≈按下点、末点=抬起点、中间点间距 ≥24px。

**坐标系往返一致性（重要）**：录制端 `EdgeGestureService.i0(x, y)`（`:1705-1718`）与执行端 `y2.w0.c(fx, fy)`（`Y2/w0.java:289-304`）用的是**完全同一套基准**：
`getRealMetrics()` 的真实宽高 → 宽/高各取 `max(1, ·)` → 比例与像素之间互转 → `coerceIn(0, 1)`。
即"录制时存的比例"在重放时会落到同一物理像素上（不受状态栏/导航栏 inset 影响，因为两边都用真实分辨率）。

### 4.2 录制会话状态 `F2.S`（真实名 `F2.S`，它就是 `MacroRecordingState`）

字段语义直接来自它自己的 `toString()`（`F2/S.java:99`）：

| 字段 | 含义 |
|---|---|
| `f2014a` targetPackage | 录制目标 App（开始录制时就定下来，也是宏的 `targetPackage`） |
| `f2015b` macroName | **录制开始时就确定的名字**（来自开始对话框） |
| `f2016c` lastInputAtMs | 上一次输入结束的时间戳（用于算下一条 `delay`；**从暂停恢复时会被重置**，见下） |
| `f2017d` isPaused | 暂停位：暂停期间触摸被忽略（`F2/C0248n.java:134`） |
| `f2018e` steps | 已录步骤（`ArrayList`） |
| `f2019f` pendingBinding | 保存后要顺手绑定的手势槽（`F2.Q`，可空） |
| `f2020g` / `f2021h` touchStartX/Y | 当前手势的按下点 |
| `i` touchStartAtMs | 当前手势的按下时间 |
| `f2022j` touchPathPoints | 当前手势的采样点表 |

### 4.3 开始录制的对话框（`G2/Q3.java:104-158`）

输入：**目标 App**（包名选择器）+ **宏名**（可空）+ 可选**顺手绑定**（side/handle + 手势类型 + z1/z2）。

校验顺序（每一步失败就 toast 并中止）：
1. 目标包名为空 → `toast_select_target_package_first`
2. 无障碍服务为空 → `toast_enable_accessibility_first`
3. 名字三级兜底：输入为空 → `default_macro_for`（**"Macro for %1$s"** / "%1$s 的宏"，`%1$s` = 包名最后一段）→ 仍空 → `default_recorded_macro`（**"Recorded %1$s"** / "已录制的 %1$s"）（`:114`、`:129-131`）；最后在落盘时还有一层 `default_recorded_macro_generic`（"Recorded Macro" / "已录制的宏"，`EdgeGestureService.java:3541-3543`）
4. **非 Pro** → `pro_required_toast`（`:124-125`）
5. `SDK < 24` → `toast_macro_requires_android_n`（`:126-127`）
6. 已在录制中 / 包名空 / `getLaunchIntentForPackage == null` → `toast_could_not_launch_app_recording`（`:128`、`:157`）

通过后：建 `MacroRecordingState(targetPackage, name, uptimeMillis(), emptyList, pendingBinding)` → `startActivity(launchIntent + NEW_TASK)` → `n0()` / `l0()`（起录制层）→ 关闭对话框（`:142-153`）
绑定对象构造：`F2.Q(handleEnum, clamp(zoneIndex, 1, 2), gestureType)`（`:138`）

### 4.4 录制控制条（3 个按钮，`E0/C0186o.java` case 4/5/6）

| 按钮 | 行为 | 证据 |
|---|---|---|
| **丢弃** | `f9379o = null` + 收起录制层 + toast `toast_recording_discarded` | `:249-255` |
| **保存** | `o0(name)` → 成功 toast `toast_macro_saved(name)`；无可保存步骤 → toast `toast_no_steps_recorded_yet`；随后跳 `SettingsActivity` | `:256-274` |
| **暂停 / 继续** | 取反 `isPaused`；**从暂停恢复时把 `lastInputAtMs` 重置为当前时间**（避免把暂停时长录成 `delay`）；刷新录制层 | `:275-286` |

### 4.5 落盘（`EdgeGestureService.o0(name)`，`EdgeGestureService.java:3516-3568`）

- 过滤：**丢弃所有 ≤80ms 的 delay**（`:3531`）；过滤后为空 → **不生成宏**（返回 null → UI 提示"还没有录到步骤"）
- 名字仍为空 → `default_recorded_macro_generic`（`:3541-3543`）
- `targetPackage` = 录制会话的 `f2014a`（`:3545`）
- `id = UUID.randomUUID()`；`createdAt = updatedAt = now`；`schemaVersion = 2`（`:3547-3550`）
- 追加到现有宏列表并**整体写回** `macros_v2_json`（`:3551-3553`）
- **可选顺手绑定**：`pendingBinding` 非空时 → `z.A0(side, zone, gestureType, new T(uuid, name))`（`:3558-3563`）

### 4.6 录制期间的触摸处理（`F2/C0248n.onTouchEvent`，`F2/C0248n.java:123-261`）

- 全屏捕获层；**落在录制控制条范围内的触摸被忽略**（`:139-153`）
- 暂停中 → 全部忽略（`:134`）
- DOWN（`:156-163`）：记按下点/时间 → 清空采样表 → 强制记入按下点 → toast `macro_status_touch_started`
- MOVE（`:167`）：按 §4.1 采样
- UP（`:178-229`）：
  - 按压时长 `l2 = clamp(now - downTime, 1, 5000)`（`:184`）
  - 距上次输入的间隔 `l4 = clamp(downTime - lastInputAtMs, 0, 10000)`；**仅 `> 80ms`** 才插入 `delay`（`:189-193`）
  - 位移 `hypot(UP-DOWN) ≤ 40px` → **`tap`**（坐标取**按下点**，时长 `clamp(l2, 30, 2000)`）（`:203-205`）
  - 否则 → **`swipe`**（起点=按下、终点=抬起、时长 `clamp(l2, 50, 5000)`、`points`=归一化后的采样表）（`:206-219`）
  - 记录后**立即回放**给用户看（`dispatchGesture`；API<24 → 遥测 `gesture_dispatch_requires_api_24`）（`:230-248`）
  - toast：`macro_status_tap_recorded` / `macro_status_swipe_recorded` / `macro_status_step_recorded`（`:229`）
- CANCEL（`:170-176`）：清空本次手势的采样与状态
- 坐标一律 `i0(x, y)` 归一化到 0~1

### 4.7 录制浮动条（原生 View 手搭，`EdgeGestureService.java:2010-2110`）

窗口（`EdgeGestureService.java:2006-2011, 2108-2111`）：

| 参数 | 值 |
|---|---|
| 宽 | `c4.l(196)` = **196 dp** |
| 高 | `WRAP_CONTENT` |
| type | `2032`（`TYPE_ACCESSIBILITY_OVERLAY`） |
| flags | `264` = `NOT_FOCUSABLE(8)` + `NOT_TOUCH_MODAL(256)` |
| format | `-3`（`TRANSLUCENT`） |
| gravity | `0x800033` = `TOP|START`（随 RTL） |
| x | `屏幕宽 − 196dp − 12dp`（右侧留 12dp） |
| y | `12dp × 4` = **距顶 48dp** |
| 可拖动 | 根布局挂了 `ViewOnTouchListenerC0221e(...)`，拖动时改 `layoutParams.x/y`（这就是 "::" 手柄的用途） |

根容器样式（`:2012-2020`）：`GradientDrawable` 底 `0xF015171D`、描边 `1dp 0xFF2E323D`、**圆角 20dp**、`elevation 10dp`、
`padding = 10dp / 9dp / 10dp / 10dp`（左/上/右/下）。

**主文字颜色是自适应算出来的**（`:2010`）：取强调色 `c02` 算亮度 `(0.299R + 0.587G + 0.114B)/255`，
**> 0.62 → 用深色 `0xFF06131D`，否则用 `0xFFF5F7FB`**（浅色强调色上换深字，保证对比度）。

视图树（两态互斥，`f9335P` 主态 / `f9337Q` 确认态）：

```
linearLayout (竖直)
├── linearLayout2  ← 主状态（this.f9335P）
│   ├── linearLayout3 (横向)
│   │   ├── View 7dp×7dp, 0xFFFF7A73, marginEnd 7dp   ← 录制红点
│   │   ├── TextView "Macro" (macro_overlay_title) 11sp 粗体 0xFFF5F7FB
│   │   ├── View 1dp×1dp 占位, marginEnd 7dp
│   │   ├── TextView "Recording" (R.string.recording) 11sp 粗体, 强调色
│   │   ├── View (0 × 1dp, weight=1)                  ← 弹簧
│   │   └── TextView "::" 14sp 0xFF8D929E 20dp×18dp 居中 ← 拖动手柄
│   ├── TextView "Actions: N" / "Actions: N - waits: M" 11sp 0xFF8D929E, padding(0,2dp,0,7dp)
│   └── linearLayout4 (横向居中) — 按钮行
│       ├── [Pause]  文字 0xFFF5F7FB, 底 0xFF242733↔0xFF2E323D, marginEnd 5dp
│       ├── [Stop]   文字 0xFFFF7A73, 底 0x1FFF7A73↔0x55FF7A73, marginEnd 5dp
│       └── [Save]   文字 = 强调色,   底 = 强调色实心,        marginEnd 0
└── linearLayout5 ← 丢弃确认态（this.f9337Q，初始 GONE）
    ├── TextView "discard_recording_question" 13sp 0xFFF5F7FB, paddingBottom 10dp
    └── linearLayout6 (横向居中)
        ├── [Discard] 文字 0xFFFF7A73, 底 0x1FFF7A73↔0x55FF7A73, marginEnd 5dp
        └── [Keep]    文字 0xFFF5F7FB, 底 0xFF242733↔0xFF2E323D, marginEnd 0
```

按钮统一样式（构造函数 `m0(...)`，`EdgeGestureService.java:630-648`）：
`TextView`，`textSize = 11sp`、粗体、`gravity = center`、`includeFontPadding = false`、
`padding(6dp, 0, 6dp, 1dp)`、`height = 32dp`、`width = 0 + weight = 1`（**等宽平分**）、`marginEnd` 由调用方给、
背景 = `d4.k(填充, 描边)` → **圆角固定 14dp**。

> **换算基准（已核）**：`c4.l(n)` 的实现是 `(int)(n × density)`，`density` 来自 `getResources().getDisplayMetrics().density`（`EdgeGestureService.java:2005` + `F2/C.java:29-36`）→ **`c4.l(n)` 就是 n dp 转 px（截断取整）**，所以本节所有 dp 数值可直接照抄。
>
> 按钮背景工厂 `d4.k(a, b)`（`F2/D.java:33-48`）：`a` = **填充色**，`b` = **描边色（可空；非空时 1dp 描边）**，**圆角固定 14dp**。注意这两个参数**不是**"常态/按下"两态 —— OHC 的按钮没有 state list，是单一静态背景。

配色（整数 → ARGB，已换算）：

| 用途 | 值 | ARGB |
|---|---|---|
| 强调色（红点 / Recording / Stop / Discard 文字） | `-34189` | `0xFFFF7A73` |
| 主文字（深色底） | `-657413` | `0xFFF5F7FB` |
| 主文字（浅色强调底时切换） | `-16379107` | `0xFF06131D` |
| 次文字（"::" / "Actions: N"） | `-7499106` | `0xFF8D929E` |
| 容器底 | `-267053283` | `0xF015171D` |
| 容器描边 / 中性按钮描边 | `-13749699` | `0xFF2E323D` |
| 中性按钮填充（Pause / Keep） | `-14407885` | `0xFF242733` |
| 强调按钮填充（Stop / Discard） | `536836723` | `0x1FFF7A73`（12% α） |
| 强调按钮描边（Stop / Discard） | `1442806387` | `0x55FF7A73`（33% α） |
| Save 按钮 | `c02.a() \| 0xFF000000`（强调色满不透明），**无描边** | 运行时算 |

> 按钮语义与状态机（`E0/C0186o.java` + `F2/C0283z.java`）：
> - 主状态：**[暂停 Pause] [停止 Stop] [保存 Save]**（标题 "Macro" + 红点 + "Recording"（"正在录制"））
> - **Stop** → 切到确认态（`C0283z` case 2：主状态 `GONE`、确认态 `VISIBLE`）：文案 **"Discard recording?"（"放弃录制？"）** + **[放弃 Discard] [保留 Keep]**
> - **Keep** → 切回主状态（`C0283z` case 3），录制内容保留
> - **Discard** → 丢弃整场录制（`C0186o` case 4，清 `f9379o` + toast "录制已丢弃"）
> - **Save** → 落盘（`C0186o` case 5）
> - **Pause** → 暂停/继续（`C0186o` case 6，恢复时重置间隔计时）

### 4.8 录制阈值一览（照抄用）

| 阈值 | 值 | 用途 |
|---|---|---|
| 采样抽稀 | 24 px | 相邻采样点最小间距 |
| 强制采样下限 | 1 px | DOWN/UP 也要求与上一点不同 |
| tap / swipe 分界 | 40 px | 按下→抬起的位移 |
| tap 时长 | clamp 30…2000 ms | 录制时 |
| swipe 时长 | clamp 50…5000 ms | 录制时 |
| delay 生成阈值 | 间隔 **> 80 ms** | 小于等于 80ms 不记 |
| delay 落盘二次过滤 | **≤ 80 ms 丢弃** | 保存时再滤一次 |

---

## 5. 触发与集成（已指令级核实）

### 5.1 只有一个执行入口（已 dex 级证明）

全 App **只有一条**宏执行路径：

```
任意触发源 → EdgeGestureService.a(service, action)        EdgeGestureService.java:519-576
  → y2.i.g(action)                                          Y2/C1502i.java
    → if (action is y2.T) C(action.macroId)                 Y2/C1502i.java:2132-2133 → :424
```

**完备性证据**（`.tmp/dex_xref.py`，扫描全部代码项的 `invoke-*` 引用）：

- `Ly2/i;->C`（宏执行入口）的调用点 = **恰好 1 处**：`Ly2/i;->g @insn+2429`
- `run_macro:` 字面量在全 dex 共 **9 处**：`A2/N;->o`(消毒)、`A2/N;->p`(面板项过滤)、`A2/z;-><init>`(v1→v2 迁移重置绑定)、`G2/V;->a`(删除清理)、`h2/a;->s`(**唯一解析器**)、`y2/T;-><init>`(构造动作)、`y2/i;->C`×2(遥测 actionId)、`y2/w0;->b`(遥测 actionId)

所以**不存在第二条宏执行路径**，也**不存在"某些触发方式不能跑宏"**：门控（Pro / SDK≥24 / 单飞 / 前台）是全局统一的。

### 5.2 动作字符串协议

- 格式：`run_macro:<macroId>`，**无编码、无转义**；持久化的只有这一串（`Y2/T.java:14`，字段 `f13392a`）
- 解析：`AbstractC0895a.s(str)` 判前缀 `run_macro:` → `new T(macroId, "Macro")`（`H2/AbstractC0895a.java:709-710`）
- 同表里的相邻协议：`launch_app:<pkg>`、`launch_app_quick:<pkg>|<uri>|<label>|<icon>`、`launch_app_activity:<pkg>|<activity>|<label>`（后两者 `|` 分隔 + `Uri.decode`），其余是裸 id（`back`/`home`/`flashlight`…），`none` → 无操作单例
- **绑定 = 动作字符串引用**：宏记录里**没有**任何反向触发列表；同一个 id 可出现在任意多处
- 宏被删后的引用处理：
  - **面板项**（`app_launcher_pinned_items` / `quick_access_items` / `quick_access_items_2` / `quick_tools_items`）→ 删除宏时**清理掉**（`G2/V.java:62-113`，匹配 `id == "action:run_macro:<id>"` 或 `actionId == "run_macro:<id>"`）
  - **手势槽位**（`gesture_<side>_z<zone>_<type>`）→ **不清理**，变成悬空引用；读取时 `A2/z.q0()` 查不到宏 → 退化为 `none` → 服务里**静默不动作**（`Y2/C1502i.java:665-668`）
  - 面板项若仍被点到 → toast `toast_macro_not_found`（`Y2/C1502i.java:424-428`）；UI 层会把它置灰（`F2/C0229g1.java:154`、`F2/C0274w.java:473`）
- 显示名**每次重新从宏表取**（不存名字）：`A2/z.java:1557-1568`、`A2/C0030a.java:68-82`、`Y2/T.java:20-26` → 手改 JSON 里的 `name` 也立即生效，绑定不变

### 5.3 触发入口表

| 入口 | 能跑宏 | 限制 | 证据 |
|---|---|---|---|
| 边缘手势槽位（左/右/下 × z1/z2 × 约 32 种手势） | ✅ | Pro；SDK≥24；全局单飞；目标 App 前台 | 键名构造 `A2/z.java:480-482`；解析 `:1202-1246`；派发 `EdgeGestureService.java:519-576`；门控 `Y2/C1502i.java:436-461,669-672` |
| 多角度/自定义拉出（`custom_pull_1..7`、`*_and_hold`） | ✅ | 同上 | `Y2/t0.java:143-169` |
| 快速访问面板项（`quick_access_items` / `_2`） | ✅ | 同上；**导入预设时这类项会被剔除** | `A2/z.java:821-854`；派发 `F1/g.java:100-102` → `E/D0.java:207-211` |
| 快捷工具面板项（`quick_tools_items`） | ✅ | 同上 | `A2/z.java:1109-1135`；派发 `F2/C0279x1.java:1086/1144` |
| 应用启动器项（`type:"action"`，`actionId:"run_macro:<id>"`） | ✅ | 同上 | `A2/z.java:267`、`F2/C0274w.java:554-600`、有效性判定 `:473` |
| 录制结束时"顺手绑定手势槽" | ✅（顺带写绑定） | 需 Pro + SDK≥24 | `G2/Q3.java:124-146` → `EdgeGestureService.java:3563` |
| 动作选择器里的 "Select Macro" 行（Presets & Macros 分类） | ❌ 只写绑定、不执行 | 非 Pro 点击弹 Pro 弹窗 | `H2/AbstractC0895a.java:475`；回调 `G2/C0333g0.java:84-89`；Pro 拦截 `G2/C0356j2.java:47-75` |
| 摇一摇 / 磁贴 / 小部件 / 快捷方式 / 外部 Intent / 设置页"试运行" | ❌ | 全库无对应实现 | 清单 `AndroidManifest.xml:48-152` 只有无障碍 + 通知监听 + billing |

**细节**：面板执行完动作通常会按设置自动收起/重开，但**显式排除宏**（`!(action is RunMacro)`）→ 跑宏时面板不会被自动收起逻辑打扰（`F2/C0274w.java:561`、`F1/g.java:103`）。

**补充核实（dex 级 xref）**：`A2/z;->Q(side, gestureType)` **不是死代码**（有 2 个调用者：`A2/z;->v @insn+34`、`A2/z;->w0 @insn+100`），但它的作用**与"能不能跑宏"无关**：
- `Q()`（`A2/z.java:1166-1190`）= **读某个手势槽位的"默认/预置映射"**：读 `M0(side,gesture)` 键 → 解析成动作 → 仅当该动作 id 出现在"已配置动作集合"里（且不是 Pro 门控动作）才返回，否则返回 `none`
- 调用者 1：`w0()`（`A2/z.java:1856-1875`）= **给 Pro 用户铺初始映射**（`starter_pro_mappings_seeded_v1` 只做一次）
- 调用者 2：`v(side, zone, gesture)`（`A2/z.java:1804-1814`）= 取槽位当前应显示/生效的动作（未同步时走 `S()` 直读）

→ 即它服务于"默认映射/预置"，不构成"某些触发方式不能跑宏"。

### 5.4 门控与前提

- **Pro 门控**：`Macros` 是 Pro 特性（`O1/a.java:1097-1099` → `z2/c.f13612t`）；非 Pro 在触发时 toast `pro_required_toast` 并中止（`Y2/C1502i.java:669-672`、`EdgeGestureService.java:521-531`），绑定/录制时也会被挡（`A2/z.java:553-567`、`G2/Q3.java:124-125`）
- **无障碍是硬前提**：`res/xml/accessibility_service_config.xml:9` 的 `canPerformGestures="true"`；宏用 `AccessibilityService.startActivity` + `dispatchGesture`（`Y2/w0.java:109-123,144`）
- **不需要悬浮窗权限**：所有面板/指示器都用 `type = 2032 (TYPE_ACCESSIBILITY_OVERLAY)`
- **不需要 root / Shizuku / shell**（全库无相关依赖）
- 动作条目自带"实验性"副标题 `macro_experimental_warning`（`Y2/T` 的默认参位掩码 `12 = 0x8|0x4` 决定；`Y2/q0.java:23-25`）

### 5.5 预设 / 备份里的宏（复刻必踩的坑）

预设与备份的消毒器 `A2/N.o(map, force)`（`A2/N.java:743-886`）对宏有**三条硬规则**：

1. `macros_v2_json` → **改写成 `"[]"`**（`:837-838`；指令级核实：先 `get(key)` → `if-eqz` 跳过 → 存在才 `put("macros_v2_json", L("[]"))`，即**只在导入的设置里确实带了这个键时才清空**）→ **宏本体从不随预设/备份迁移**
2. 面板项里 `type=action` 且 `actionId` 以 `run_macro:` 开头 → **丢弃该项**（`p()`，`:920-926`）
3. `gesture_*` 里的 `run_macro:` 值 → **保留**（`:874-876`）。
   **指令级核实**（`LA2/N;->o` insn+755~788）：`f3/b;->c0`（`removePrefix`）→ `f3/i;->O`（`startsWith("run_macro:")`）→ `if-eqz` 跳过 / 否则 `new A2.L(...)` + `Map;->put`。即**该值被 put 回 map（保留方向）**，不是丢弃。
   → 跨设备导入后成为**悬空引用**，运行时退化为 `none`
   （旁证：这一段旁边还有第 6 个乱序诱饵 `'m:cmar_oru'` ⇄ `'run_macro:'`）

**⚠️ 最容易踩的坑**：应用**内置预设**走的是 `b(id)`（`A2/N.java:355-379`），**不过消毒器**，而内置预设里 `macros_v2_json` 就是 `"[]"` → **应用一次内置预设会把用户所有宏清空**。

v1→v2 迁移（`A2/z.java:151-172`，构造 PrefsManager 时执行一次）：删掉旧键 `macros_json`、置 `macros_v2_reset_done=true`，并把**所有 `run_macro:` 手势键重置为 `none`**（旧宏不迁移）。

---

## 6. 编辑器 UI 与交互

**重要结论：V1.2.6 没有"步骤编辑器"。** 宏的创建只有一条路：**录制**；已有的宏只能被**选中绑定**或**删除**。全工程只有两处 `new AppMacro(...)`（反序列化 `A2/z.java:404`、录制保存 `EdgeGestureService.java:3550`），没有任何"加一步/删一步/改一步"的入口。

| 功能 | 有无 | 位置/文案 |
|---|---|---|
| 开始录制对话框（选目标 App + 填名字 + 可选绑手势） | ✅ | `G2/Q3.java:104-158`；默认名见 §4.3；门控 Pro / API≥24 |
| 录制控制条：**丢弃 / 保存 / 暂停继续** 三个按钮 | ✅ | `E0/C0186o.java:249-286`；文案 `toast_recording_discarded`（"录制已丢弃"）、`toast_macro_saved`（"宏已保存：%1$s"）、`toast_no_steps_recorded_yet`（"尚未记录步骤"） |
| 录制中的状态提示 | ✅ | `macro_status_touch_started`（"已开始触摸"）/ `macro_status_tap_recorded` / `macro_status_swipe_recorded` / `macro_status_step_recorded`（`F2/C0248n.java:162,229`） |
| 选择宏（绑定到某个动作位） | ✅ | 动作选择器 "Presets & Macros" 分类下的 **Select Macro** 行（`H2/AbstractC0895a.java:475`）；选择回调 `G2/C0333g0.java:84-89`；非 Pro 拦截 `G2/C0356j2.java:47-75`、`G2/C0370l2.java:110-122` |
| 删除宏（清理面板引用） | ✅ | `G2/V.java:62-113`（清宏表 + 清 `app_launcher_pinned_items`/`quick_access_items(_2)`/`quick_tools_items`；**不动手势键**） |
| 重命名 | ❌ | 无入口；但直接改 JSON 的 `name` 立即生效（显示名每次从宏表取） |
| 步骤增删改 / 排序 | ❌ | 无 |
| 参数表单（坐标/时长） | ❌ | 无（坐标只能靠"录制"产生） |
| 导入 / 导出宏 | ❌ | 无（预设/备份都**故意排除宏本体**，见 §5.5） |
| 列表展示 | ✅ | 面板项/选择器里显示宏名与步骤数（`W1/C1395a.java:525`、`G2/C0322e3.java:196`） |

### 6.1 宏 UI 的四个界面（按资源键反查实现类）

| 界面 | 实现类 | 关键元素（资源键） |
|---|---|---|
| **Select Macro（宏选择/管理页）** | `G2/r.java`（搜索框 `:321`）、`G2/C0415s.java`（空态 `:467`）、`G2/C0322e3.java`（行摘要 `:212`） | `select_macro_title`="Select Macro"；搜索框 `search_macros_placeholder`="Search macros..."；空态 `no_macros_yet`="No macros yet"；New Macro 按钮 `new_macro`="New Macro"；行摘要 `macro_row_summary`="%1$s - %2$d %3$s"（名字 - 动作数 action/actions）；删除 `content_delete_macro`="Delete macro" + 确认框 `delete_macro_title`/`delete_macro_message`="Delete '%1$s'? This cannot be undone." |
| **Create Macro（新建/录制页）** | `G2/V2.java`（保存校验 `:72-88`）、`G2/Q3.java`（开始录制 `:104-158`） | 标题 `create_macro_title`="Create Macro"；名字输入 `macro_name_label`="Macro name"；**名字必填**（空 → `toast_macro_name_required`="Macro name is required"）；目标 App 选择器；Start Recording；帮助文案 `macro_recording_idle_help`="Tap Start Recording to launch the target app, interact with it, then tap Save in the floating overlay." / `macro_recording_active_help`="Recording is active. Interact with the target app, then tap Save in the floating overlay to finish and return here." |
| **录制浮动条** | 文案生成 `EdgeGestureService.java:4210`；按钮 `E0/C0186o.java:249-286` | 标题 `macro_overlay_title`="Macro"；实时计数 `macro_overlay_actions_count`="Actions: %1$d" 或 `macro_overlay_actions_waits_count`="Actions: %1$d - waits: %2$d"（**actions = 非 delay 步数，waits = delay 步数**，与执行期 `O1.o.O()` 的口径一致）；三个按钮：保存 / 暂停继续 / 丢弃 |
| **动作行（动作选择器里）** | `P2/AbstractC1137b.java:1839-1853`、`H2/AbstractC0895a.java:709` | 显示名 `action_run_macro`="Run Macro: %1$s"；副标题 `macro_experimental_warning`="Records and replays taps and swipes in the selected app." |

### 6.2 两条保存路径（行为不同，别混）

| 路径 | 名字校验 | 无步骤时 |
|---|---|---|
| **Create Macro 页的 Save**（`G2/V2.java:72-88`） | **必填**：空 → `toast_macro_name_required` | toast `toast_no_recorded_steps` |
| **录制浮动条的 Save**（`E0/C0186o.java:256-274`） | 不校验（录制开始时已由 `G2/Q3` 三级兜底出名字） | toast `toast_no_steps_recorded_yet` |

两条路径最终都调 `EdgeGestureService.o0(name)`（§4.5）落盘；成功后浮动条路径还会跳 `SettingsActivity`。

> 对移植的含义：OHC 把"编辑"完全交给了录制，所以**录制器的分段规则就是编辑器的全部**；XGesture 若要做手编 UI，属于**超出 OHC 的增强**（不是复刻）。

---

## 7. 移植规格（XGesture 侧落地）

### 7.1 XGesture 现有能力盘点（已核）

| 能力 | 现状 | 位置 |
|---|---|---|
| 手势注入（路径 + 时长） | **有**：`dispatchPointerSwipePath(service, path, durationMs, maxDurationMs, onFinished)`、`dispatchPointerTap(...)`、`dispatchPointerHold(...)`，都是 `dispatchGesture` | `app/src/main/java/com/slideindex/app/service/SlideIndexAccessibilityGestureInjector.kt`（`:140,214,321,344`） |
| 手势时长上限 | `MAX_HOLD_DURATION_MS = 5000`、`MAX_RECORDED_GESTURE_DURATION_MS = 5000`、`DEFAULT_SWIPE_MAX_DURATION_MS = 800` | 同文件 `:799-804` |
| 指针轨迹录制 | **有（但临时）**：记录 `GestureRecorderTrailPoint(x, y, durationMs)`，最多 200 点，录完**立即回放**，**不落盘** | `overlay/FloatingPointerGestureRecorder.kt`（`MAX_POINTS=200 :346`、`REPLAY_START_DELAY_MS=120 :340`、`MIN_REPLAY_TRAVEL_PX=24 :342`、`MIN_HOLD_REPLAY_MS=200 :344`） |
| 轨迹可视化 | **有**：悬浮指针 + 轨迹拖尾 | `FloatingPointerSession.kt`、`FloatingPointerVisuals.kt`（`GestureRecorderTrailPoint :44`） |
| 前台 App 判定 | **有**：无障碍 `AccessibilityForegroundResolver` / `OverlayService.foregroundPackage` | `service/SlideIndexAccessibilityForegroundTracker.kt`、`overlay/EdgeOverlayHost.kt:297` |
| 拉起 App | **有**：`getLaunchIntentForPackage` + `FLAG_ACTIVITY_NEW_TASK`（多处） | 例：`util/SystemGestureActions.kt` |
| 宏容器 / 步骤模型 / 持久化 | **没有** | — |
| 宏录制（触摸→tap/swipe/delay 分段） | **没有**（现有录制只存"一整条轨迹"） | — |
| 宏编辑 UI | **没有** | — |

**结论**：注入原语、轨迹采集、前台判定、拉 App 都已具备 → 移植主要是**新增宏的数据模型 + 执行器 + 分段录制 + 编辑 UI**，不需要新的系统权限。

### 7.2 数据结构（与 OHC 的 JSON 100% 兼容）

```kotlin
// 与 macros_v2_json 一一对应；坐标统一为 0..1 比例
sealed interface MacroStep {
    data class Delay(val durationMs: Long = 200L) : MacroStep
    data class Tap(val x: Float = 0.5f, val y: Float = 0.5f, val durationMs: Long = 80L) : MacroStep
    data class Swipe(
        val startX: Float = 0.5f, val startY: Float = 0.8f,
        val endX: Float = 0.5f, val endY: Float = 0.2f,
        val durationMs: Long = 250L,
        val points: List<Point> = emptyList(),   // [0]=按下点 … [last]=抬起点（与 OHC 录制一致）
    ) : MacroStep { data class Point(val x: Float = 0.5f, val y: Float = 0.5f) }
}

data class Macro(
    val id: String,                 // UUID
    val name: String = "Untitled Macro",
    val targetPackage: String,
    val createdAtMs: Long, val updatedAtMs: Long,
    val schemaVersion: Int = 2,
    val steps: List<MacroStep>,
)
```

**JSON 读写的硬性要求**（照抄 OHC 才能互通）：
- 存储键 `macros_v2_json`（字符串内容是数组）；默认 `"[]"`；写回用**压缩** `toString()`（无空格）
- 宏键序：`schemaVersion,id,name,targetPackage,createdAtMs,updatedAtMs,steps`（`JSONObject` 保序）
- step 键序：`delay` → `type,durationMs`；`tap` → `type,x,y,durationMs`；`swipe` → `type,startX,startY,endX,endY,durationMs,points`
- **只写 `durationMs`，不写 `holdMs`**（`holdMs` 只用于读兼容）
- `id`/`targetPackage` 为空白 → **丢弃该条宏**；`steps` 缺失 → `[]`
- 解析异常 → 整个列表当空（容错优先）；未知 `type` → 跳过该步
- 不做稳定排序（顺序 = 列表顺序），新增宏 append
- 坐标一律 0..1 比例；`tap` 默认 (0.5,0.5)/80ms；`swipe` 默认 (0.5,0.8)→(0.5,0.2)/250ms；`delay` 默认 200ms

### 7.3 执行器（行为等价，含全部常量）

```kotlin
// 进程级互斥：任一宏在跑就拒绝（OHC 不比较 id）
private val macroLock = Any()
private var runningMacroId: String? = null

fun run(macro: Macro) {
    if (Build.VERSION.SDK_INT < 24) { toast(R.string.macro_requires_android_n); return }
    synchronized(macroLock) {
        if (runningMacroId != null) { toast(R.string.macro_already_running); return }
        runningMacroId = macro.id
    }
    thread(name = "macro-${macro.id}") {
        try { execute(macro) } finally { synchronized(macroLock) { runningMacroId = null } }
    }
}

private fun execute(macro: Macro) {                    // 工作线程
    val ctx = service ?: return
    val intent = ctx.packageManager.getLaunchIntentForPackage(macro.targetPackage)
    if (intent == null) { toast(R.string.target_app_not_found); return }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)

    val t0 = SystemClock.uptimeMillis()
    var waited = 0L; var reached = false
    while (true) {                                     // 轮询：100ms / 上限 4500ms
        waited = SystemClock.uptimeMillis() - t0
        if (waited >= 4500L) break
        if (currentForegroundPackage() == macro.targetPackage) { reached = true; break }
        Thread.sleep(100L)
    }
    if (!reached) { toast(R.string.macro_app_timeout); return }
    Thread.sleep(250L)                                 // settle

    var budget = waited + 250L                         // ★ 启动耗时抵扣预算
    var discountDone = false                           // ★ 一旦执行过非 Delay 步就停止抵扣
    for (step in macro.steps) {
        val fg = currentForegroundPackage()
        if (fg != null && fg != macro.targetPackage) { toast(R.string.macro_foreground_changed); return }

        var effective = step
        if (!discountDone && step is MacroStep.Delay) {
            effective = MacroStep.Delay(maxOf(0L, step.durationMs - budget))
            budget = maxOf(0L, budget - step.durationMs)
        }
        val ok = when (effective) {
            is MacroStep.Delay -> {
                if (effective.durationMs == 0L) continue          // 0 延迟：跳过且不影响折扣状态
                Thread.sleep(effective.durationMs.coerceIn(0L, 10_000L)); true
            }
            is MacroStep.Tap -> {
                val p = toPixels(effective.x, effective.y)          // clamp01 × getRealMetrics
                showIndicator(p)
                dispatchPath(Path().apply { moveTo(p.x, p.y) },
                             effective.durationMs.coerceIn(1L, 2000L))
            }
            is MacroStep.Swipe -> {
                val s = toPixels(effective.startX, effective.startY)
                val e = toPixels(effective.endX, effective.endY)
                val dur = effective.durationMs.coerceIn(50L, 5000L)
                showIndicator(s); postDelayedIndicatorTo(e, dur)
                val path = Path().apply {
                    moveTo(s.x, s.y)
                    if (effective.points.size < 2) lineTo(e.x, e.y)
                    else effective.points.drop(1).forEach { lineTo(toPixels(it.x, it.y)) }
                }
                dispatchPath(path, dur)
            }
        }
        if (!ok) { toast(R.string.macro_step_failed); return }        // 失败即中止
        if (step !is MacroStep.Delay) discountDone = true
    }
    toast(R.string.macro_complete, macro.name)
}

// 派发：主线程 dispatchGesture + 等 duration+1500ms；onCompleted=true，onCancelled/超时=false
private fun dispatchPath(path: Path, durationMs: Long): Boolean { … }
```

**照抄时有 3 个"看起来像 bug 但必须知道"的点**：
1. 目标 App 已在前台时**也照样** `startActivity`（OHC 不短路）→ 等效实现要不要复刻，需产品决策（我建议**不复刻**，仅当不在前台才拉起；差异只在"已在目标 App"时少一次任务栈顶重置）。
2. 未知步骤类型会抛异常 → 被外层 `catch (Exception)` 吞掉 → 误报"找不到目标应用"（我建议**不复刻**，直接按"步骤类型不支持"提示）。
3. 中止检测粒度是"步"：长 delay 期间切走 App 不会被立刻发现（我建议**改进**：在 sleep 期间分片轮询前台）。

### 7.4 录制器（分段与采样规则）

- 位移 **≤ 40px → `tap`**（坐标取**按下点**，时长 `clamp(按压时长, 30..2000)`）
- 否则 → `swipe`（起点=按下、终点=抬起、时长 `clamp(按压时长, 50..5000)`、`points`=[按下点, …采样…, 抬起点]）
- **轨迹采样**：距上一采样点 **≥ 24px** 才记一点；DOWN/UP 强制记（但要求与上一点 **≥ 1px**）；**不设点数上限**
- 两次输入之间的间隔 `l4 = clamp(本次按下 - 上次结束, 0..10000)`，**仅当 `> 80ms`** 才插 `delay`
- 落盘前**再过滤一次** `≤80ms` 的 delay
- **暂停**：暂停期间不录；**从暂停恢复时把"上次输入时间"重置为当前时间**（否则暂停时长会变成一条巨大的 delay）
- 录完**立即回放**该手势（视觉预览）
- 坐标 `i0(x,y)` 归一化到 0..1

> XGesture 侧对接提示：现成的 `FloatingPointerGestureRecorder` 已有 `MIN_REPLAY_TRAVEL_PX=24`（凑巧与 OHC 的采样阈值同值）与 `MAX_POINTS=200` 上限；OHC 侧**没有**点数上限，移植时若沿用 200 上限，长手势会被截断 —— 需要显式决策。

### 7.5 分阶段落地建议

| 阶段 | 内容 | 复用 |
|---|---|---|
| P0 | `MacroRepository`（`macros_v2_json` 读写 + 上面全部容错规则）+ 单测（往返、容错、默认值） | 新增 |
| P1 | `MacroEngine`（执行器 + 常量 + 互斥）+ 一个"跑宏"动作挂到动作列表 | `SlideIndexAccessibilityGestureInjector`、前台跟踪 |
| P2 | 手编编辑器（列表 + 步骤增删改排序 + tap/swipe 参数表单 + 坐标选择器） | Compose 现有设置页范式 |
| P3 | 录制器（分段规则 + 阈值 + 立即回放预览 + 顺手绑定手势） | `FloatingPointerGestureRecorder`、`FloatingPointerSession` |
| P4 | 导入/导出（JSON 文本分享，可与 OHC 的 `macros_v2_json` 互通——因为格式一致） | 现有分享/剪贴板工具 |

**与 XGesture 动作系统的接线点（已核，改动很小）**：
- `GestureAction` 已有 `abstract val payload: String`（`core/gesture/src/main/java/com/slideindex/app/gesture/GestureAction.kt:245`）和工厂 `GestureAction.from(type, payload)`（`:959`）
- 存储协议是 `"<typeId>|<payload>"`（`QuickLauncherItem.encodeActionPayload`，`core/gesture/.../launcher/QuickLauncherItem.kt:152`；正文 `encodeActionBody :171`），且所有设置路径（边缘手势键、快捷启动器、角落径向菜单…）都复用这一套 → **新增带参动作不需要动存储协议**
- 落地只需三步：① `GestureActionType` 里加一个 `MACRO(id)`；② `data class RunMacro(val macroId: String) { override val payload = macroId }`；③ `from()` 里加分支
- 动作字符串建议直接沿用 OHC 的 **`run_macro:<id>`** 作为"外部可交换表示"（导入/导出宏与手势配置时可互通）；内部 payload 用裸 id 即可

**移植时建议**：XGesture 侧**不要**复刻 OHC 的两个坑 —— ①目标 App 已在前台仍强拉 `startActivity`；②未知步骤/异常被吞后误报"找不到目标应用"。另外建议把"中止检测"从"步粒度"改进为"sleep 期间分片轮询前台"。

## 8. 待办与存疑

**已完成（两轮）**
- [x] 数据模型 / JSON Schema / 读写容错 / 默认值 / v1→v2 迁移 → §2
- [x] 步骤全集（只有 `delay`/`tap`/`swipe`）与全部参数、默认值、执行期 clamp → §2.3 + §3.6
- [x] 执行引擎（时序、抵扣公式、并发单飞、失败与中止、无障碍交互、线程模型）→ §3
- [x] 录制器（采样抽稀 24px、40px tap/swipe 分界、80ms delay 阈值、暂停语义、保存与绑定）→ §4
- [x] 录制浮动条**像素规格**（196dp 宽、右留 12dp、距顶 48dp、圆角 20dp、全部配色、可拖动）→ §4.7
- [x] 触发入口全集 / `run_macro:<id>` 协议 / 绑定模型与僵尸引用 / Pro 门控 / 预设清空宏 → §5
- [x] 编辑器 UI 现实情况（**没有步骤编辑器**；Select Macro + Create Macro + 录制浮动条，两条保存路径）→ §6
- [x] 移植规格（数据结构、执行器伪代码、录制阈值、XGesture 能力盘点与接线点、分阶段计划）→ §7
- [x] 修正分析工具 bug（`tools/dex_method_dump.py` 指令长度表 0xB0–0xCF）→ §1
- [x] **dex 级完备性证明**（自写 `.tmp/dex_xref.py`）：
      `Ly2/i;->C` 调用者恰好 1 处；`run_macro:` 全库 9 处且唯一解析器；`macros_v2_json` 的 `const-string` 4 处（1 读 + 1 写 + 消毒器 + 键集合）、`z.s0` 调用者恰好 2 处
- [x] **`A2/z;->Q` 不是死代码**（2 个调用者），语义 = 默认/预置映射解析器，**与宏触发范围无关** → §5.3 补充
- [x] **UI 的像素换算基准核完**：`c4.l(n) = (int)(n × density)`（= n dp，截断）；按钮背景工厂 `d4.k(fill, stroke)` = 填充 + 可选 1dp 描边 + **圆角 14dp**（原先把参数误当"常态/按下"两态，已改正）→ §4.7

**仍待补（非阻塞）**
- [ ] 实机验证：真实 prefs 里 `macros_v2_json` 的字面形态（数字格式、键序）；以及 `"id":null`、`durationMs:"abc"` 这类脏值的真实行为

**已知边界（明确不改的）**
- **宏功能的"行为"部分已 100% 覆盖**（数据、执行、录制、触发、门控、覆盖层像素规格）。
- **两个 Compose 页面**（Select Macro 列表页、Create Macro 表单页）只做了元素级盘点（控件/文案/校验/交互），**没有逐像素测量 Compose 布局尺寸**——这两个页面属于标准列表/表单，移植时按 XGesture 自己的设计语言实现即可，不需要照抄像素。

**存疑（已在各节标注）**
- `A2/z.Z()` = isPro：**32 个调用点**全是 `if (!Z()) → 要 Pro` 的形态，故"= isPro"成立；但 `Z()` 自身的字面级解码在我这里仍读出 `? true : true`（jadx 伪影在指令层也解释不通），属强推断而非逐位证明 —— **不影响宏实现，只影响门控**
- `A2/N.java:874-876`：已指令级确认走 `Map.put`（保留方向），但**无法 100% 排除 put 进去的值被改写**
- `Path` 的 float→int 取整方向（截断或四舍五入由 Android framework 决定）
