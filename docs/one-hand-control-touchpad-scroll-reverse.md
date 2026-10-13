# One Hand Control 触控板（Touch Pad）与"页面滚动"完整逆向

> 目标：按与宏（macro）相同的标准，把「One Hand Control V1.2.6 MOD」里"在屏幕上滚动页面"这件事逆到可移植深度。
>
> 样本：`D:\Downloads\Quick Share\One Hand Control MOD APK V1.2.6 (Pro Unlocked).apk`
> 反编译产物：`.ohc_decompiled/`；工具：`tools/dex_method_dump.py`、`.tmp/dex_xref.py`、`.tmp/insn_window.py`、`.tmp/strict_dump.py`
>
> ⚠️ 本报告只留本地，**不提交、不公开**（分析对象是破解版 APK；且本仓库是开源仓库，见 §9）。

---

## 0. 结论速览（先回答"是不是悬浮窗控滚动"）

**是悬浮窗，但不是"滚动条"，而是一块触控板（Touch Pad）。** 它本身**不直接滚动**，滚动是它派发出去的**合成滑动手势**：

| 问题 | 答案 |
|---|---|
| 有悬浮窗吗？ | **有**：`F2.f2`（真实名 `LF2/f2;`，日志 tag `TouchPadOverlay`，705 行），`type = 2032`（`TYPE_ACCESSIBILITY_OVERLAY`） |
| 它在屏幕上显示什么？ | 一块可拖动/可缩放的半透明触控板 + 一个**光标**（`F2.Z1`） |
| 拖动触控板 = 滚动？ | **不是**。**拖动 = 移动光标**（灵敏度放大） |
| 那滚动怎么做？ | **"双击后拖动"** → 从**光标位置**派发一次 `moveTo → lineTo` 的**滑动手势**（这就是页面滚动/拖页） |
| 用的什么机制？ | 无障碍 `dispatchGesture`（`GestureDescription` + `StrokeDescription`）——**不是**无障碍节点滚动 |
| 有独立的"上滚/下滚"动作吗？ | **没有**。53 个动作里没有任何滚动动作；解析器里的 `scroll_top` 是**废弃 id**（映射到 `none`、无动作类、无文案）；`ACTION_SCROLL_*` 只出现在 `p1/*` 的调试名字表里，不是 OHC 的功能 |
| 需要什么权限？ | 仅无障碍（`canPerformGestures`）；Pro 特性名 **`TouchPad`**；API ≥ 24 |

触控板自己的提示文案可直接佐证（`strings.xml` → `touch_pad_tip_body`）：

> 英文：Tap anywhere on the pad to click at the cursor. **Drag to move the cursor.** Long-press the pad to trigger a hold at the cursor position. **Double-tap then drag to perform a swipe from the cursor.**
> 中文：在触控板任意位置点击，可在光标处点击。**拖动以移动光标。** 长按触控板，可在光标位置保持按下。**双击后拖动，可从光标位置执行滑动。**

---

## 1. 形态判定（为什么不是"无障碍滚动"）

排查过程与证据：

| 排查项 | 结论 | 证据 |
|---|---|---|
| 动作表里有没有 scroll 动作？ | **没有**（53 个动作全集已枚举） | 各动作单例：`Y2/*.java`（`new q0("id", "Label", "Desc", …)`），例如 `Y2/c0.java:9` 的 `show_touch_pad` |
| 有没有 `ACTION_SCROLL_FORWARD/BACKWARD` 调用？ | **没有**（只有名字表） | 全库仅 `p1/C1134g.java:64,66,92,96`、`p1/C1133f.java:66,68` —— 那是无障碍动作的**调试名字表** |
| `scroll_top` 是什么？ | **废弃 id**：解析成 `none`，无动作类、无文案 | `H2/AbstractC0895a.java:592` → `return C1492E.f13295f`（= `none`）；全库无第二处出现 |
| 那"滚动"落在哪？ | **触控板的 slide** | §3、§4 |
| 全库有几处合成手势派发点？ | **只有两处**：①宏引擎 `F2/RunnableC0215c.java:170`（`y2.w0` 调用）②**触控板** `F2/f2.java:127,146,215`。第三处 `B2/b.java:169` 经查只服务**宏录制器的即时回放**（唯一构造点 `F2/C0248n.java:254`，`B2.b` case 2），不是独立功能 | 全库 `dispatchGesture` 引用共 7 处（含 `B2/b.java` 的变量声明与调用） |

---

## 2. 触控板窗口与视图

（待补：并行线在量触控板本体与提示卡的完整像素规格；下面是已核实部分）

### 2.1 触控板本体（窗口 + 视图，已核）

**窗口**（`F2/f2.java:478-482`）：

| 参数 | 值 |
|---|---|
| 尺寸 | 宽 `i(touch_pad_size_dp)`、高 = **宽 + 32dp**（顶部头部条） |
| type / flags / format | `2032` / `808` / `-3` |
| flags = 808 | `0x328` = `LAYOUT_NO_LIMITS(512) | LAYOUT_IN_SCREEN(256) | NOT_TOUCH_MODAL(32) | NOT_FOCUSABLE(8)` → **可触摸**（没有 `NOT_TOUCHABLE`） |
| gravity | `0x800033` = `TOP|START` |
| x / y | 由位置管理器给：x clamp 在 `[margin, screenW − w − margin]`，y 同理（`:460-481`） |
| 注册 | `G.t.t(TOUCH_PAD, params, w, h, f(8), f(24))` → 交给"位置/吸附管理器"，**边距 8dp / 24dp**（`:482`） |
| 位置恢复 | `j(displayMetrics, params, w)`（`:236-…`、`:483`）；恢复失败会 `Log.e("TouchPadOverlay", "Failed to restore touch pad position")`（`F2/RunnableC0215c.java:164`） |
| **位置记忆** | 键 = **`floating_panel_touch_pad_x_ratio` / `floating_panel_touch_pad_y_ratio`**（键模板 `"floating_panel_" + panelId + "_" + x|y + "_ratio"`，`A2/z.java:278-280`），值是 **0~1 的归一化比例**（float），读写都被夹进允许区（`G/t.java:734-759, 772-787`） |
| 位置记忆开关 | 仅在 **`floating_panel_remember_position == true`** 时才记/读（**默认 false** → 默认不记忆），对所有浮动面板统一生效（`G/t.java:737, 775`） |

**宽度换算**（`f2.i(displayMetrics, size)`，`:226-234`）：

```kotlin
w = dp(clamp(size_dp, 160, 320))                      // 默认 160
maxW = min(screenW - dp(24), (screenH - dp(96)) - dp(32))
if (w > maxW) w = maxW
return max(w, dp(160))                                // 兜底 ≥160dp
```

**视图与配色**（`:324-345`）：

| 元素 | 规格 |
|---|---|
| 容器 | `FrameLayout`；底 `alpha(opacity,12..100) │ 0x10131A`（RGB 16,19,26）；**描边 1dp `0xFF252A33`**；**圆角 14dp**；`elevation 8dp`；`clipToOutline = true` |
| 头部条 | 高 **32dp**（= 高度多出来的那 32dp）；横向 `LinearLayout`、`gravity = center_vertical`、`padding = 8dp / 0 / 6dp / 0`；底 `alpha(clamp(opacity+6,12,100)) │ 0x151820`（RGB 21,24,32）；**圆角：上 14dp、下 0** |
| 关闭图标 | 头部内 `点击 → 关闭触控板`（`ViewOnClickListenerC0236j(a2Var, 3, this)`，`:484`） |
| 换算 | `f2.f(int)` / `f2.g(float)` = `值 × density` → **dp→px**（`:183-189`） |
| 提示卡（首次，`touch_pad_tip_seen_once`） | 标题 `touch_pad_tip_title`、正文 `touch_pad_tip_body`、按钮 `touch_pad_tip_got_it`（`:171,586-619`）；宽度 = `min(屏幕宽 − 32dp, 340dp)`，**下限 240dp**（`:587-589`）；窗口 `(w, -2, 2032, 776, -3)`（`:658`）→ flags `0x308` = `LAYOUT_NO_LIMITS | LAYOUT_IN_SCREEN | NOT_FOCUSABLE` |

### 2.2 光标浮层（独立于触控板，单独一个 window）

| 项 | 值 | 证据 |
|---|---|---|
| 尺寸 | `f(36)` = **36dp × 36dp** | `F2/f2.java:572-574` |
| 窗口 | `LayoutParams(36dp, 36dp, type = 2032, flags = 792, format = -3)` | `:574` |
| flags = 792 | `0x318` = `NOT_FOCUSABLE(8) | NOT_TOUCHABLE(16) | NOT_TOUCH_MODAL(256) | LAYOUT_NO_LIMITS(512)` → **纯视觉、不拦截触摸** | `:574` |
| gravity | `0x800033` = `TOP|START` | `:575` |
| 定位 | 以光标点为**中心**：`x = cursorX − 18dp`，`y = cursorY − 18dp`（`f(36)/2`） | `:576-578`、`:696-698` |
| 颜色 | `zVar.T().a()` ← 偏好 `touch_pad_cursor_color_rgb`（默认 `0xFFFFFF`），统一补满 alpha：`0xFF000000 \| color` | `:573`、`A2/z.java:808,1249`、`F2/Z1.java:42` |
| 绘制 | **自绘 View**（`Z1 extends View`）；半径 = `min(宽,高) × 0.22`（36dp 盒 → **半径 7.92dp ≈ 直径 15.8dp**）的单色实心圆 | `F2/Z1.java:14,79,99` |
| 外圈 | 第二个 Paint：`STROKE`、**线宽 `density × 2` = 2dp**、颜色 `(color & 0xFFFFFF) \| 0x99000000`（alpha 153） | `F2/Z1.java:49-53` |
| 状态 | `Y1` 枚举三态：**`NONE(0)` / `SLIDE(1)` / `HOLD(2)`** | `F2/Y1.java:25-30` |
| 状态动画 | **NONE**：静态实心圆 ·**SLIDE**：520ms 循环脉冲环（半径 `min → 2.1×min`，alpha `190 → 0`）·**HOLD**：720ms 正弦**呼吸变色**（在光标色与 `0xFF7AF3D2` 之间插值） | `F2/Z1.java:80-98` |
| 状态切换 | 滑动模式 → `SLIDE`；按住模式 → `HOLD`；否则 `NONE` | `F2/f2.java:159-167`（`d()`）、`:276-284`（`m()`） |

### 2.3 自动关闭计时

**30 秒无操作自动收起**：每次交互结束都会 `l()` → `removeCallbacks + postDelayed(30_000)`；到点执行 `k()`（`F2/f2.java:269-274`、`:243-256`、`A2/Q.java:140`）。

### 2.4 拖动触控板本体（移动窗口）

面板根布局挂了 `OnTouchListener`（`F2/f2.java:490-544`，jadx 标为 `F2.X1` 的匿名类）：

| 事件 | 行为 |
|---|---|
| DOWN | 记下窗口当前 `x/y` 与手指 `rawX/rawY`；复位"已移动"标志；**重置 30 秒计时**（`l()`） |
| MOVE | `x = 起始x + (rawX − 按下rawX)`，`y` 同理 → 交给位置管理器 `G.t.k(params, w, h, 8dp, 24dp)` 做**吸附/边界约束**后 `updateViewLayout` |
| UP | 若移动过 → `G.t.u(TOUCH_PAD, params, w, h, 8dp, 24dp)` → **吸附并持久化位置** |

> 与宏录制浮动条一样，"拖动"是挂在根布局上的，但触控板多了一层**位置管理器**（含 8dp/24dp 边距与吸附），并且位置会被记住。

### 2.5 面板内的尺寸滑条（`F2.a2` = `touch_pad_size_slider`）

**尺寸不是"拖拽手柄"，而是面板里一条自绘滑条**：

| 项 | 规格 | 证据 |
|---|---|---|
| 轴 | 横向，两端各留 **14dp**；轨道 3dp 圆头；已选段 3dp 圆头；**滑块圆点半径 6dp** | `F2/a2.java:38-54,81-87` |
| 颜色 | 轨道 `0x667A8294`（蓝灰 40% α）·已选段 `0xCC8DEBFF`（浅蓝 80% α）·滑块 `0xFFF5F7FB` ·背景 `0xD612141C` | `F2/a2.java:38,43,47,52` |
| 背景 | 圆角矩形，**半径 = 高度/2**（胶囊） | `:80` |
| 取值 | 触摸 x → 比例（去掉 14dp 内边距）→ `size = clamp(round((f×160 + 160)/16)×16, 160, 320)` → **量化到 16dp 一档**（160,176,…,320 共 11 档） | `:58-73` |
| 交互 | MOVE 实时更新预览并重绘；UP 时才提交，且**只在值变了才回调** | `:90-114` |
| 提交动作 | `W1.l(size)` → 写 `touch_pad_size_dp`（clamp 160..320）→ **立即按 `高 = 32dp + i(size)` 改窗口** | `F2/W1.java:60-72` |
| 显隐 | 滑条**默认 `GONE`**（`setVisibility(8)`），初值 = 当前 `touch_pad_size_dp` | `F2/f2.java:396-399` |
| 触发显隐 | 点/拖**调整大小图标**（`F2.T1` case 1 = `touch_pad_resize_icon`，**30dp × 30dp**，`TOP|START`，`leftMargin 5dp`、`topMargin 1dp`）来切换滑条 | `F2/f2.java:389-394`、`F2/T1.java:22-40` |

面板头部的另外两个元素：**关闭图标** `F2.C0248n` case 2，布局 **24dp × 24dp**，点击关闭（`F2/f2.java:349-352`、`F2/C0248n.java:57`）。

### 2.6 关闭路径

1. **头部关闭图标**（`ViewOnClickListenerC0236j(a2Var, 3, this)`，`F2/f2.java:484`）→ `k()`
2. **30 秒无操作自动收起**（§2.3）
3. 关闭动作 `k()` 会：取消计时器 → **派发未完成的 hold**（`h()`）→ 复位光标 → 移除提示卡 → 从 WindowManager 移除面板（`F2/f2.java:243-256`）
4. 再次触发 `show_touch_pad` 时的行为：`if (this.f2281g != null) return;` → **已开着就忽略**（不重复创建，`F2/f2.java:569-571`）

---

## 3. 交互状态机（已完整解出）

实现：`F2.c2`（触控区 View 的 `onTouchEvent`，`F2/c2.java:192-351`）＋ `F2.f2` 上的状态字段。

### 3.1 状态字段（`c2` 内）

| 字段 | 含义 |
|---|---|
| `f2216e` | `touch_pad_hold_enabled`（默认 true） |
| `f2217f` | `touch_pad_double_tap_timeout_ms`（默认 230，clamp 120..450） |
| `f2218g` | `touch_pad_hold_delay_ms`（默认 420，clamp 250..900） |
| `f2220j,f2221k` | 本次按下的落点 |
| `f2222l,f2223m` | 上一次事件的位置（用于算增量） |
| `f2224n` | 上一次"单击"的抬手时间（判双击） |
| `f2225o` | 已移动标志（>15px 后置位；同时取消长按计时） |
| `f2226p` | **滑动模式**（双击后拖动已越过 15px） |
| `f2227q` | **按住模式**（长按计时已到，正在按住） |
| `f2228r` | **本次按下是"双击的第二击"** |
| `f2229s` | 有一次单击在等第二击（未被 double-tap 超时清理） |
| `f2231u` / `f2232v` | 两个延时任务：双击超时到点 / 长按延迟到点 |

### 3.2 关键判定

```kotlin
// DOWN (F2/c2.java:201-218)
isSecondTap = pendingSingleTap && (now - lastTapUpAtMs <= doubleTapTimeoutMs)
if (isSecondTap) { cancel(doubleTapTimeoutTask); pendingSingleTap = false }
downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y
moved = false; sliding = false; holding = false
if (holdEnabled && !isSecondTap) postDelayed(holdTask, holdDelayMs)   // 第二击不再武装长按

// MOVE (F2/c2.java:288-347)
dxTotal = ev.x - downX; dyTotal = ev.y - downY; dist = hypot(dxTotal, dyTotal)
if (sliding) { accumulateSlideDelta(ev.x - lastX, ev.y - lastY) }        // 见 §3.3
else if (holding) { moveCursor(ev.x - lastX, ev.y - lastY); holdEnd = cursor }
else {
    if (isSecondTap && dist > 15f) {                                     // 进入滑动模式
        sliding = true
        slideAnchor  = cursor                                                // 锚点 = 当前光标
        slideEnd     = cursor
        setCursorPressed(true)
        accumulateSlideDelta(dxTotal, dyTotal)                              // 用"从第二次按下起的累计位移"打底
    } else if (!moved && dist > 15f) { moved = true; cancel(holdTask) }      // 移位即取消长按武装
    if (moved && !isSecondTap) moveCursor(ev.x - lastX, ev.y - lastY)        // 首击拖动 = 移动光标
}
lastX = ev.x; lastY = ev.y

// UP (F2/c2.java:220-260)
cancel(holdTask); dist = hypot(ev - down)
if (sliding) {
    slideDist = hypot(slideEnd - slideAnchor)
    if (slideDist >= dp(8)) dispatchSlide(slideAnchor, slideEnd, prefs.touch_pad_slide_duration_ms)
    else                     dispatchTap(cursor)                         // 位移不足 → 退化成点击
    setCursorPressed(false)
} else if (holding) { dispatchHold(); ... }
else if (isSecondTap && dist <= 15f) { dispatchTap(cursor) }              // 双击（第二击没移动）
else if (!moved && dist <= 15f) { lastTapUpAtMs = now; pendingSingleTap = true; postDelayed(doubleTapTimeoutTask, doubleTapTimeoutMs) }
```

**为什么单击要延迟**：单击的 tap 不是立刻派发，而是等 `double_tap_timeout_ms` 过去（`f2231u`），以便区分"单击"和"双击后拖动"。这是"双击后拖动 = 滑动"能成立的前提。

### 3.3 滑动/光标的位移换算（同一个放大系数）

`f2.c(...)`（光标）与 `C1395a.F(...)`（滑动终点累积）用的是**同一个公式**（`F2/f2.java:152-157`、`W1/C1395a.java:453-459`）：

```kotlin
factor = (touch_pad_cursor_sensitivity / 100f) * 1.8f      // 默认 100 → 1.8
cursorX = clamp(cursorX + dx * factor, 0, realWidth)
cursorY = clamp(cursorY + dy * factor, 0, realHeight)
// slide：
slideEndX = clamp(slideEndX + dx * factor, 0, realWidth)
slideEndY = clamp(slideEndY + dy * factor, 0, realHeight)
```

即：**页面滑动的距离 = 手指位移 × 灵敏度放大（默认 ×1.8）**，并且**起点锚在光标上**（不是手指位置）。

---

## 4. 手势派发矩阵（照抄用）

| 输入 | 派发 | 起点 | 终点 | 时长 | 阈值/条件 | 证据 |
|---|---|---|---|---|---|---|
| 单击（位移 ≤15px） | **tap** | 光标位置 | — | **50ms 固定** | 等过 double-tap 超时才派发 | `F2/f2.java:133-150`、`E0/O.java:15-16` |
| 双击（第二击未移动） | **tap** | 光标位置 | — | 50ms | ≤15px | `F2/c2.java:244-250` |
| 拖动（首击） | *不派发*，仅移动光标 | — | — | — | >15px | `F2/c2.java:334-345` |
| **双击后拖动**（≥8dp） | **slide** | 光标锚点 | 光标锚点 + 手指位移×灵敏度×1.8（clamp 屏幕） | `touch_pad_slide_duration_ms`（默认 **130**，clamp 80..500） | 抬手时位移 ≥ **8dp** | `F2/c2.java:222-232`、`W1/C1395a.java:453-459` |
| 双击后拖动（<8dp） | 退化成 **tap** | 光标 | — | 50ms | <8dp | `F2/c2.java:230-232` |
| 按住（≥ hold 延迟） | **long-press stroke** | 按住点 | — | `ViewConfiguration.getLongPressTimeout() + 180` | 需 `hold_enabled` | `F2/f2.java:191-219` |
| 按住后拖动（≥10dp） | 追加一发 **slide** | 按住点 | 移动后的点 | **固定 220ms** | 手势 `onCompleted` 时判 ≥**10dp** | `F2/e2.java:36-39`、`F2/f2.java:215` |
| API < 24 | 只打日志（`Touch Pad … requires Android 7.0+`），不派发 | — | — | — | — | `F2/f2.java:117-119,137-139,200-202` |

派发方式与宏的**关键差异**：
- 触控板是**发射后不管**：`dispatchGesture(build, null, null)` —— 无回调、**不等待完成**（`F2/f2.java:127,146`）
- 宏执行器则是**等结果**：`dispatchGesture(...)` + `CountDownLatch.await(duration+1500)`，把成功/取消当步骤成败（`Y2/w0.java:109-123`）
- 触控板手势**不抛异常处理**：只在 `try/catch` 里打日志（`Log.e("TouchPadOverlay", …)`）

---

## 5. 设置项与入口 UI

### 5.1 全局设置页「触控板设置」（`touch_pad_prefs_title`）

页面骨架：`G2/P5.java:18140-18228`（状态初值）→ 内容 lambda `G2/L4.java:344-355`（分区）→ 具体条目在 `G2/C0350i3.java`（行为）/ `G2/C0452x4.java`（外观）/ `G2/W2.java`（高级）

| 分区 | 条目 | 偏好键 | 默认 | 范围 | 控件 | 证据 |
|---|---|---|---|---|---|---|
| **行为** | 触控板不透明度 | `touch_pad_opacity` | 88 | **12–100** | 滑条（单位 %） | `G2/C0350i3.java:115`、`G2/P5.java:18146` |
| **行为** | 光标灵敏度 | `touch_pad_cursor_sensitivity` | 100 | **40–240** | 滑条 | `G2/C0350i3.java:128`、`G2/P5.java:18181` |
| **行为** | 按住触摸（+副标题"长按触控板以在光标处保持"） | `touch_pad_hold_enabled` | true | — | 开关 | `G2/C0350i3.java:140-141`、`G2/P5.java:18158` |
| **外观** | 光标颜色 | `touch_pad_cursor_color_rgb` | `0xFFFFFF` | — | 取色器 | `G2/C0452x4.java:189`、`A2/z.java:808,1249` |
| **高级** | 双击时间 | `touch_pad_double_tap_timeout_ms` | 230 | **120–450**（显示为 `<值>ms`） | 滑条 | `G2/W2.java:113-125`、`G2/P5.java:18163` |
| **高级** | 按住触发延迟（**仅当"按住触摸"开启时显示**） | `touch_pad_hold_delay_ms` | 420 | **250–900** | 滑条 | `G2/W2.java:127-140`、`G2/P5.java:18169` |
| **高级** | 滑动时长 | `touch_pad_slide_duration_ms` | 130 | **80–500** | 滑条 | `G2/W2.java:142-153`、`G2/P5.java:18175` |

**"尺寸"不在设置页里**：`touch_pad_size_dp` 由**面板内的自绘尺寸滑条**（`F2/a2` = `touch_pad_size_slider`，规格见 §2.5）修改 → **量化 16dp 一档**写入偏好，并**立即**按 `高 = 32dp + i(size)` 改窗口尺寸（`F2/W1.java:60-72`）。（另有 `F2/T1` = `touch_pad_resize_icon` 作为该区域的可访问性标注。）

**动作级高级区**：`show_touch_pad` 这个动作自己在动作设置里也带一个 "高级" 折叠区，包含**双击时间 / 按住触发延迟（条件显示）/ 滑动时长**三项（`G2/W2.java` 整个就是它，由 `G2/L4.java:355` 的 `P5.f(...)` 挂上）。

### 5.2 死键 / 死资源

| 名称 | 状态 | 证据 |
|---|---|---|
| `touch_pad_pass_through_outside_taps`（偏好键） | **死键**：全库只有 `A2/N;-><clinit>`（备份/预设键集合）引用，1.2.6 **无任何读取点** | `A2/N.java:44` + `.tmp/dex_xref.py --string …` |
| `touch_pad_tap_hint`（字符串"点击以单击，拖动以移动光标"） | **死资源**：只在 `R.java` 有 id，**代码从未引用** → 该提示在 1.2.6 不会显示 | `R.java:1827`；全库 grep 无第二处 |
| `scroll_top`（动作 id） | **死 id**：解析成 `none`，无动作类、无文案 | `H2/AbstractC0895a.java:592` |

（待补：设置页分组/顺序、每个键的 UI 标签英文原文、`pass_through` 的语义）

---

## 6. 入口与门控

| 项 | 内容 | 证据 |
|---|---|---|
| 动作 | `show_touch_pad`（"Show Touch Pad" / "显示触摸板"） | `Y2/c0.java:9`；解析 `H2/AbstractC0895a.java:694` |
| 描述 | "Floating trackpad for cursor-controlled taps anywhere on screen." / "可在屏幕任意位置使用光标点击的浮动触控板。" | `strings.xml` |
| Pro | 特性名 **`TouchPad`** | `z2/c.java:13`；`R.java: pro_feature_touch_pad` |
| API | ≥ 24（否则 toast `toast_touch_pad_requires_android_n`） | `F2/f2.java:305` |
| 触发入口 | 与其它动作一样：任意手势槽位 / 面板项（无按触发方式的差异） | 复用宏那边的结论（唯一动作派发链 `EdgeGestureService.a → y2.i.g`） |
| 设置入口 | 触控板设置页（`touch_pad_prefs_title`） | `G2/L3.java:57`、`G2/P5.java:18195` |

---

## 7. 与 XGesture 的映射（移植规格 + 缺口）

### 7.1 XGesture 现有能力（已核）

| 能力 | 现状 | 位置 |
|---|---|---|
| 任意路径 + 时长的手势注入 | ✅ `dispatchPointerSwipePath(service, path, durationMs, maxDurationMs, onFinished)` | `app/src/main/java/com/slideindex/app/service/SlideIndexAccessibilityGestureInjector.kt:344` |
| 点击 / 长按注入 | ✅ `dispatchPointerTap(...)`（`:140`）、`dispatchPointerHold(...)`（`:321`，clamp 20ms..`MAX_HOLD_DURATION_MS`=5000） | 同文件 |
| **发射后不管**式派发（不等回调） | ✅ 上述 API 的 `onFinished` 可传空实现；`fastVerticalScroll` 就是不等结果（`:1164`） | `:1140-1169` |
| 手势式"快速滚动" | ✅ `SCROLL_TO_TOP(27)` / `SCROLL_TO_BOTTOM(28)` → `fastVerticalScroll`：到顶 = 中心点向下拖 `Int.MAX_VALUE`（120ms）；到底 = 最多 `SCROLL_BOTTOM_STROKE_COUNT` 笔、每笔间隔 80ms、时长 12ms 的快速上滑 | `GestureAction.kt:467-472`、`SlideIndexAccessibilityService.kt:1140-1169` |
| **节点滚动**（无障碍） | ✅ `performScrollableNodeAction(...)` → `ACTION_SCROLL_FORWARD/BACKWARD`（屏搜场景用） | `SlideIndexAccessibilityGestureInjector.kt:585-613` |
| 悬浮窗 / 可拖动面板 / 指针可视化 | ✅ 多个 overlay host + `FloatingPointerSession`/`Visuals` 的拖尾 | `app/src/main/java/com/slideindex/app/overlay/` |
| 动作系统与设置页范式 | ✅ `GestureActionType` + `payload` 字符串协议 + `QuickLauncherItemCodec` | `core/gesture/.../GestureAction.kt:245,959`、`QuickLauncherItem.kt:152` |
| **触控板（面板 + 光标 + 状态机）** | ❌ 没有 | — |

### 7.2 结论：这是"新增一个动作 + 一个面板"，不是"改滚动实现"

OHC 的"页面滚动"**不是**一个滚动动作，而是一块**触控板**；XGesture 已有 `SCROLL_TO_TOP/BOTTOM`（手势）与节点滚动（屏搜），**都不需要改**。要移植的是**触控板这一整套**：

| 要移植的 | 内容 | 复用 |
|---|---|---|
| 动作 | 新增 `GestureActionType`（如 `TOUCH_PAD(id)`）+ `GestureAction.ShowTouchPad`，`payload` 空串 | 现有动作协议与选择器 |
| 面板窗口 | `type=2032`、`flags=808`、`gravity=TOP|START`、宽 = `clamp(size_dp,160,320)` 受屏幕约束、高 = 宽 + 32dp、圆角 14dp、描边 1dp、底色 `alpha│0x10131A`、头部 `alpha+6│0x151820` | `OverlayHost`/`FloatingPointer*` 的建窗与拖动范式 |
| 面板拖动 + 位置记忆 | 拖动改 `x/y` → 吸附（边距 8dp/24dp）→ 松手写归一化比例键 | 现有 panel 拖动/持久化（如有可复用） |
| 调整大小手柄 | 拖动改 `touch_pad_size_dp`（160–320，**量化 16dp 一档**）并**实时**改窗口尺寸 | 新增（自绘滑条，规格见 §2.5） |
| 光标浮层 | 36dp、`flags=792`、以点居中、半径 = 边长×0.22、2dp 外圈、三态动画（静态/520ms 脉冲/720ms 呼吸） | `FloatingPointerVisuals` 的自绘范式 |
| **交互状态机** | 单击延迟派发 / 双击后拖动 → slide / 首击拖动 = 移动光标 / 按住后拖动 = 追加 slide / 30s 自动收起 | 新增（本节 §3 可 1:1 照抄） |
| 手势派发 | tap = 单点 stroke **50ms**；slide = `moveTo+lineTo` + `touch_pad_slide_duration_ms`（默认 130）；按住后拖动 = **220ms** | `dispatchPointerTap` / `dispatchPointerSwipePath`（**注意**：宏那篇报告里提到的 `buildClampedPath`/`sanitizeSwipeEndpoints` 会改坐标，触控板要的是"从光标到光标+位移"的原样注入，需确认是否绕过） |
| 设置页 | 行为（不透明度 12–100、灵敏度 40–240、按住触摸）/ 外观（光标颜色）/ 高级（双击 120–450、按住延迟 250–900、滑动时长 80–500） | 现有设置页范式 |

**移植时明确不要做的**：不要抄它的中文/英文文案、不要抄它的配色常量（`0x10131A` 等）作为"设计"、不要把类名/字段名带进代码或注释（见 §9）。

### 7.3 移植后的差异（可接受）

- OHC 的触控板手势**不等结果**；XGesture 的注入器有回调 → 保持"不等"即可行为一致
- OHC 用 `getRealMetrics` 做坐标基准；XGesture 注入器内部会 clamp 路径到屏幕，需确认其 clamp 不会改变"光标 + 位移"的终点（若会，直接走 `service.dispatchGesture(...)` 的原样路径）

---

### 7.4 照抄清单（行为等价的最小集合）

1. `factor = (sensitivity/100) × 1.8`，**光标与滑动终点共用**，终点 clamp 到屏幕
2. 单击要**延迟到双击超时之后**才派发（否则"双击后拖动"无法成立）
3. 双击后拖动：**>15px 才进入滑动模式**（锚点取当前光标）→ 抬手 **≥8dp 才派发 slide**，否则退化成 tap
4. 首击拖动 = 移动光标、**不派发任何手势**；移动 >15px 会取消长按武装
5. 按住后拖动 ≥10dp → 追加一发 **220ms** 的 slide（固定值，不读偏好）
6. tap stroke 固定 **50ms**；slide 时长读 `touch_pad_slide_duration_ms`（默认 130）
7. 触控板手势**不等结果**、失败只打日志；**30 秒无操作自动收起**
8. 面板尺寸/透明度/灵敏度/颜色全部走偏好，尺寸**只在面板上拖**（设置页里没有）

## 8. 待办与存疑

**已完成（本轮）**
- [x] 形态判定：**不是**无障碍节点滚动、**没有**滚动动作；滚动 = 触控板的 slide（§0/§1）
- [x] 交互状态机全解（单击延迟/双击后拖动/按住后拖动/移动光标）（§3）
- [x] 手势矩阵与全部阈值、时长（50ms / 130ms / 220ms / 15px / 8dp / 10dp / 420ms / 230ms）（§4）
- [x] 触控板本体像素规格（窗口 flags 808、宽×高 = size + 32dp、圆角 14dp、描边 `0xFF252A33`、底色 `0x10131A`+alpha、头部 `0x151820`+alpha、elevation 8dp）（§2.1）
- [x] 光标浮层像素规格（36dp、flags 792、半径 0.22×边长、2dp 外圈、三态动画 520ms/720ms）（§2.2）
- [x] 30 秒自动收起、拖动本体、位置记忆键与开关（§2.3-2.5）
- [x] `touch_pad_pass_through_outside_taps` = **死键**（dex 级 xref：全库只有备份键集合引用）(§5)
- [x] 入口/门控（`show_touch_pad`、Pro `TouchPad`、API≥24）（§6）
- [x] 全库只有两处合成手势派发点（宏引擎 + 触控板），排除"还有别的滚动实现"（§1）

**仍待补（非阻塞）**
- [ ] 长按路径的完整时序：按住→（`hold_delay` 到点置 `f2293t`）→移动时同步更新 hold 终点 → 抬手 `h()` 派发长按（`getLongPressTimeout()+180`）→ 回调 `e2` 在 `onCompleted` 时若位移 ≥10dp **追加一发 220ms slide**。已还原到这里，但"长按 stroke 完成"与"用户抬手"谁先谁后未在真机确认

**存疑**
- `F2/X1` 是 jadx 内联出来的匿名 `OnTouchListener`（没有独立文件），其"点击穿透/外部点击"语义只能从 `f2.java:490-544` 读；未见"点面板外部关闭面板"的实现
- `touch_pad_pass_through_outside_taps` 虽是死键，但可能对应一个**未完成的设计意图**（点面板外部时把触摸让给下面 App）——移植时不必实现

## 9. 处理方式（合规）

- 本报告**只留本地**，不提交进本仓库（本仓库是开源仓库，见风险讨论）
- 反编译产物 `.ohc_decompiled/`、`.ohc_apk/`、`.ohc_jadx.log` 与破解版 APK 一律不进版本库
- 移植时**只对齐行为与参数**，代码/文案/视觉按 XGesture 自己的实现重写
