# XGesture 手势宏（Macro）实施计划

> 目标：在 XGesture 里落地一套"手势宏"功能 —— 把一串"等待 / 点击 / 滑动"记录成宏，之后用一个手势一键重放到目标 App 里。
>
> 约束（硬性）：
> 1. **只实现行为与数据格式**；代码、文案、视觉全部自己写。**禁止**把任何逆向产物（`.ohc_*`、`docs/one-hand-control-*-reverse.md`、破解 APK）或其中的类名/字段名/资源名带进代码或注释。
> 2. **不动用户未提交的在制品**：`app/src/main/java/com/slideindex/app/gesture/ActionExecutor.kt`、`app/src/main/java/com/slideindex/app/overlay/ScreenPinManager.kt`、`app/src/main/java/com/slideindex/app/overlay/quickmenu/*`、`app/src/main/res/values*/strings.xml`（4 个）。其中 `ActionExecutor.kt` 的接线由负责人最后统一处理。
> 3. **新增字符串走独立资源文件**：`app/src/main/res/values/strings_macro.xml` + `values-zh/strings_macro.xml`（不碰现有 `strings.xml`）。
> 4. 每次编辑文件后自检行尾：`python scripts/normalize-eol.py --check <文件>`（报"需要规范化"就 `--write`）。提交前 `git diff --numstat` 的删除行数必须等于真实改动行数。
> 5. **不要并发跑 Gradle**（会和别人的构建抢 KSP 生成目录）。编译验证由负责人统一在最后跑一次：`.\gradlew.bat :app:compileFullDebugKotlin --offline`。

---

## 1. 数据模型（`core/gesture`，新增包 `com.slideindex.app.macro`）

文件：`core/gesture/src/main/java/com/slideindex/app/macro/MacroModel.kt`

```kotlin
package com.slideindex.app.macro

/** 宏里的一步。坐标一律是屏幕宽/高的比例 0f..1f。 */
sealed interface MacroStep {
    /** 纯等待。 */
    data class Wait(val durationMs: Long = DEFAULT_WAIT_MS) : MacroStep

    /** 单击（点一下）。坐标取按下点。 */
    data class Tap(
        val x: Float = 0.5f,
        val y: Float = 0.5f,
        val durationMs: Long = DEFAULT_TAP_MS,
    ) : MacroStep

    /** 滑动。points 含首尾（[0] 是按下点、last 是抬起点）。 */
    data class Swipe(
        val startX: Float = 0.5f,
        val startY: Float = 0.8f,
        val endX: Float = 0.5f,
        val endY: Float = 0.2f,
        val durationMs: Long = DEFAULT_SWIPE_MS,
        val points: List<PathPoint> = emptyList(),
    ) : MacroStep {
        data class PathPoint(val x: Float = 0.5f, val y: Float = 0.5f)
    }

    companion object {
        const val DEFAULT_WAIT_MS = 200L
        const val DEFAULT_TAP_MS = 80L
        const val DEFAULT_SWIPE_MS = 250L
    }
}

/** 一条宏：绑定到一个目标 App，由若干步组成。 */
data class Macro(
    val id: String,
    val name: String,
    val targetPackage: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val steps: List<MacroStep> = emptyList(),
) {
    /** 会动手势的步数（不含等待）——列表里显示"名字 - N actions"用这个。 */
    val actionCount: Int get() = steps.count { it !is MacroStep.Wait }
    val waitCount: Int get() = steps.count { it is MacroStep.Wait }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val FALLBACK_NAME = "未命名宏"
    }
}
```

## 2. JSON 读写（同文件或 `MacroJson.kt`）

- 存储键：`macros_v2_json`（存的是**字符串**，内容是 JSON 数组）—— 保留该键名是为了能直接导入/导出同类应用的宏数据。
- 写出格式（键序固定，压缩无空白）：

```json
[{"schemaVersion":2,"id":"…","name":"…","targetPackage":"…","createdAtMs":0,"updatedAtMs":0,
  "steps":[{"type":"wait","durationMs":200},
           {"type":"tap","x":0.5,"y":0.5,"durationMs":80},
           {"type":"swipe","startX":0.5,"startY":0.8,"endX":0.5,"endY":0.2,"durationMs":250,
            "points":[{"x":0.5,"y":0.5}]}]}]
```

- 类型字段值：`"delay"` / `"tap"` / `"swipe"`（**兼容既有数据，读的时候认得 `"delay"`**；我们自己写出去也用 `"delay"` 以保证双向兼容）。
- 读取容错（逐条实现，写单测）：
  - 整个 JSON 解析失败 → 返回空列表（不抛异常）
  - 宏缺 `id` 或 `targetPackage`（空白）→ **丢弃该条宏**
  - `name` 缺失/空白 → `Macro.FALLBACK_NAME`；`createdAtMs`/`updatedAtMs` 缺失 → 当前时间
  - `steps` 缺失/非数组 → 空列表
  - 步骤 `type` 不认识 → **跳过该步**
  - 各字段缺省值见上表；解析阶段**不做 clamp**（clamp 只在执行期，见 §4）
- 提供：
  ```kotlin
  object MacroJson {
      fun decode(raw: String?): List<Macro>
      fun encode(macros: List<Macro>): String
  }
  ```

## 3. 存储（`core/gesture` 只放纯逻辑；落盘放 `feature/settings` 或 `app`，由 P0 负责人按仓库现有 `SettingsRepository` 约定实现）

- 需要一个 `MacroRepository`（Hilt `@Singleton`）：`fun all(): List<Macro>` / `fun upsert(macro: Macro)` / `fun delete(id: String)` / `fun byId(id: String): Macro?`，内部就是读写上面的字符串键。
- 删除宏时**不得**动手势绑定（绑定是"按 id 引用"，读不到就退化成无操作）；面板里的宏按钮由 P1 的清理逻辑处理（本阶段可只做基本删除）。
- 单测：`core/gesture/src/test/java/com/slideindex/app/macro/MacroJsonTest.kt` —— 往返、缺字段、脏值、未知 type、坏 JSON、空数组。

## 4. 执行引擎（`app`，新增 `com.slideindex.app.macro.MacroEngine`）

行为规范（**照抄语义、自己写实现**）：

1. `SDK_INT < 24` → 直接拒绝（提示一句）。
2. 进程级互斥：已有宏在跑 → 拒绝并提示"宏正在运行"；否则占位，`finally` 释放。
3. 起独立工作线程执行（不要占用主线程）。
4. `packageManager.getLaunchIntentForPackage(targetPackage)`；为空 → 提示"找不到目标应用"并结束。
5. `addFlags(FLAG_ACTIVITY_NEW_TASK)` → `startActivity`。
6. **等目标到前台**：每 100ms 查一次，总上限 4500ms（超时 → 提示"应用没能在限定时间内打开"并结束）。
7. 到前台后 `Thread.sleep(250)`。
8. `budget = 实际等待耗时 + 250`，`discountDone = false`。
9. 逐步执行：
   - 每步开始前查前台包；**非空且 ≠ 目标** → 提示"已切换到其它应用，宏停止"并结束
   - `Wait`：若 `!discountDone` → `effective = max(0, duration - budget)`、`budget = max(0, budget - duration)`；`duration == 0` 直接跳过；否则 `sleep(clamp(effective, 0, 10000))`
   - `Tap`：比例 → 像素（`getRealMetrics`，比例先 clamp 到 0..1，宽高各取 `max(1, ·)`）；`Path.moveTo(x, y)` 单点；注入手势，时长 `clamp(duration, 1, 2000)`
   - `Swipe`：起点/终点同样换算；`dur = clamp(duration, 50, 5000)`；路径 = `moveTo(起点)`，若 `points.size < 2` → `lineTo(终点)`，否则 → **`points.drop(1)` 逐点 `lineTo`**（首点是按下点，已被 moveTo 覆盖；此时**不再追加终点**）
   - 其余类型 → 视为失败
10. 手势注入：走无障碍 `dispatchGesture`；等待 `时长 + 1500ms`；`onCompleted` = 成功，`onCancelled` 或超时 = 失败 → 提示"第 N 步执行失败"并结束。
11. 全部完成 → 提示"宏已完成：<名字>"。
12. 复用仓库现有注入能力（`SlideIndexAccessibilityGestureInjector`）**之前先确认它不会改写我们的坐标/时长**（它内部有 `buildClampedPath` / `sanitizeSwipeEndpoints` / 时长上限；若会改写，就在引擎里走一条不做这些加工的注入路径）。

## 5. 动作系统接线（负责人自己做）

- `GestureActionType` 新增 **`RUN_MACRO(96)`**（95 已被占用）。
- `GestureAction` 新增：
  ```kotlin
  data class RunMacro(val macroId: String) : GestureAction() {
      override val type = GestureActionType.RUN_MACRO
      override val payload = macroId
  }
  ```
- `GestureAction.from()` 增加分支；动作目录/选择器加条目（放在"宏"分类下，显示为"运行宏：<宏名>"，读不到宏时置灰）。
- 具体派发：
  - `app/src/main/java/com/slideindex/app/gesture/ActionExecutor.kt`（**用户在改，最后统一处理**）
  - `app/src/main/java/com/slideindex/app/gesture/GestureSessionActionDispatch.kt`

## 6. 录制器（`app`，新增包 `com.slideindex.app.macro.record`）

把用户的真实触摸录成步骤（阈值是行为的一部分，必须一致）：

- **采样抽稀**：仅当与上一次采样点距离 **≥ 24px** 才记一点（用 `hypot`）；按下/抬起**强制采样**，但仍要求与上一点 **≥ 1px**；**不设点数上限**。
- 按下：记录按下点与时间，清空本次手势的采样表。
- 抬起：
  - 按压时长 `clamp(now - downTime, 1, 5000)`
  - 与"上次输入结束"的间隔 `clamp(downTime - lastInputAt, 0, 10000)`，**仅 `> 80ms`** 才插入一条 `Wait`
  - 位移 `hypot(up - down) ≤ 40px` → **`Tap`**（坐标取**按下点**，时长 `clamp(按压时长, 30, 2000)`）
  - 否则 → **`Swipe`**（起点=按下、终点=抬起、时长 `clamp(按压时长, 50, 5000)`、`points` = 归一化后的采样表）
  - 记完**立即回放一次**给用户看
- 取消：丢弃当前手势的采样。
- **暂停**：暂停期间不记录；**从暂停恢复时把"上次输入时间"重置为当前时间**（否则暂停时长会变成一条巨大的等待）。
- 保存：**丢弃所有 ≤ 80ms 的 `Wait`**；过滤后为空则不保存并提示"还没录到步骤"。
- 落盘：`id = UUID.randomUUID().toString()`、`createdAt = updatedAt = now`、`schemaVersion = 2`。

### 录制浮动条（视觉规格，自己实现，不用对方的资源）

- 窗口：宽 **196dp**、高 wrap、右边缘留 **12dp**、距顶 **48dp**；不可聚焦、不拦截触摸；**可拖动**（拖动改 x/y）。
- 容器：填充 `#F015171D`、1dp 描边 `#2E323D`、圆角 **20dp**、elevation 10dp、内边距 10/9/10/10（dp）。
- 主状态一行：红点 7dp `#FF7A73` + 文案"宏"（11sp 粗体，文字色按强调色亮度自适应：`(0.299R+0.587G+0.114B)/255 > 0.62` 用 `#06131D`，否则 `#F5F7FB`）+ "录制中" + 可拖动手柄。
- 第二行：`动作 N`（或 `动作 N · 等待 M`），11sp，色 `#8D929E`。
- 按钮行（等宽平分、高 32dp、圆角 14dp、11sp 粗体、内边距 6/0/6/1 dp、间距 5dp）：
  - 主状态：**暂停**（填充 `#242733`、1dp 描边 `#2E323D`）/ **停止**（填充 `#1FFF7A73`、1dp 描边 `#55FF7A73`、文字 `#FF7A73`）/ **保存**（填充=强调色实心、无描边）
  - 点"停止"切到确认态：文案"放弃这段录制？" + **放弃** / **保留**；"保留"切回主状态。

## 7. 界面（`feature/settings`，新增包 `com.slideindex.app.settings.macro`）

- **宏列表页**：标题、搜索框（按名字过滤）、空态文案"还没有宏"、行摘要 = `名字 - N 个动作`、新建按钮、删除（带确认弹窗：`删除"%1$s"？此操作不可撤销。`）。
- **宏编辑页**：名字输入（**必填**，空 → 提示"请填写宏名称"）、目标 App 选择器、`开始录制`按钮、帮助文案（空闲态："点开始录制会启动目标应用；在里面正常操作，然后点悬浮条上的保存。"；录制中态："录制中：在目标应用里操作，完成后点悬浮条上的保存返回这里。"）。
- 全部文案自己写（英文放 `values`、中文放 `values-zh` 的 `strings_macro.xml`）。
- 注册入口：按仓库现有设置页的注册/导航方式接入（自己找现成范式，不要新造一套）。

---

## 8. 阶段与验收

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 | 模型 + JSON + 存储 + 单测 | 单测通过；`git diff --numstat` 行数合理；行尾自检 LF |
| P1 | 引擎 + 动作接线 | `:app:compileFullDebugKotlin --offline` 通过 |
| P2 | 录制器 + 浮动条 | 同上 |
| P3 | 列表页 + 编辑页 | 同上 |

**未做/待确认**（必须在报告里写清）：
- 是否复用 `SlideIndexAccessibilityGestureInjector` 还是另起注入路径（见 §4.12）
- 面板/预设里的宏引用清理策略
- 真机验证：录制 → 保存 → 手势触发 → 目标 App 内重放是否一致
