# 用户反馈分析（2026-10-14）

> 本文的分析部分（§1–§6）为纯调研结论，不含改动。**§7 记录了本轮实际落地的实现**（第 1/2/4/5 条）与两处自主决策。
> 结论标注 `相对路径:行号` 证据，并区分「已证实」（读过代码/日志）与「推测」。
> 仓库版本：1.41.0（versionCode 77，`build.gradle.kts:41-42`），分支 main，工作区干净（`git status` 无本次任务外改动）。
> 行号均以**当前工作区**为准。仓库里另有一份 `docs/user-feedback-analysis-2026-10-05.md`，其中取词面板一节的描述在本版本**已过时**（详见第 1 条「对旧文档的纠正」），不要引用其行号。
> ⚠️ 阅读顺序提示：§1–§6 的行号是**改动前**的行号；§7 落地后部分行号已漂移。

## 0. 结论速览

| # | 反馈 | 性质 | 现状是否成立 | 改动面 | 建议优先级 |
| :- | :- | :- | :- | :- | :- |
| 1 | 取词图片面板高度太低，希望按截图大小自适应 | 体验缺陷（**根因与用户描述不同**） | **成立，但「自适应」已实现**，真问题是高度预算太小 | 小（1 处分支） | **高**（性价比最高） |
| 2 | 应用索引的字母索引支持位置上下调整 | 新功能，**确实缺失** | **成立** | 小~中（几何 1 处 + 设置链路 ~12 文件） | **高**（与第 1 条可同批） |
| 3 | 蜂窝/其它启动器：应用打不开，前几次能开后面不行 | **真 bug，已有高置信度根因** | **成立**（启动器窗口的「存活判据」在无障碍实例重建后会永久骗人） | 中（补代次判据，逐个启动器） | **最高** |
| 4 | 全息启动器改为自添加应用 | 产品形态变更 | **成立**（现为「全部应用 + 黑名单」） | 中（~8–12 文件，1 个核心改动点） | 中 |
| 5 | 快速启动器非编辑页去掉下方 +/− 或改长按编辑 | 交互精简 | **成立** | 小（显示条件）+ 需定替代入口 | 中 |

**一句话排序**：第 3 条最痛（永久性打不开、只能强停 App）且已有高置信度根因，但**第一步是 5 分钟的区分实验**而非改代码；第 1、2 条是「用户能看到、改动小、不破现有约束」的干净优化，建议紧随其后同批做；第 4 条架构上很干净但要先定默认值产品决策；第 5 条实现小但替代入口的长按**已被占用**，要先设计。

---

## 1. 取词面板的图片高度：不是「没自适应」，而是「预算被文本区吃掉了」

### 现状（已证实）

**图片尺寸本来就是按截图宽高比算的**，链路如下：

- 基准尺寸 = 截图位图（有 `screenRect` 时按其屏幕矩形映射）转 dp，**保宽高比、且永不放大**：`app/src/main/java/com/slideindex/app/overlay/pickresult/PickResultImageSearchBar.kt:142-177`（:153-164 取基准、:165-176 夹取）与 `:116-135`（`applyPickResultImageDisplayCaps`：放得下就原样 :125-127，否则先按宽、再按高等比缩 :128-134）。
- 夹取上限：`maxHeight = min(effectivePanelMaxImageHeight, 屏高)`，其中「竖屏上限 = 屏宽」恒成立（`PickResultImageSearchBar.kt:106-114`，横屏才是 `min(屏宽, 屏高×45%)`）。
- 而 `effectivePanelMaxImageHeight` 又被一个**极小的「可负担高度」**二次夹住：`app/src/main/java/com/slideindex/app/overlay/pickresult/FloatBallPickResultContent.kt:251-262`
  ```
  affordableImageMaxHeight = maxPanelHeight − reservedForTextAndChrome − imageSectionFixedChrome   (下限 48dp)
  effectivePanelMaxImageHeight = min(屏宽, affordableImageMaxHeight)
  ```
  各项来源：`maxPanelHeight = 屏高 × 0.85`（`OverlayBottomPanelMetrics.kt:14-20`、`:72-79`，`FloatBallPickResultContent.kt:167-168`）；`reservedForTextAndChrome`（`:240-250`）= 面板上下 padding 24 + 文本区 chrome 96 + 最小文本正文 220 + 图文分隔线 10 + 搜索网格预留 159；`imageSectionFixedChrome` = 图片区自身 chrome 58（`:235-239` → `PickResultImageSearchBar.kt:179-189`）。
- 数量级（**推测**，取决于 dpi/字体缩放/字号设置）：1080×2340@440dpi（393×851dp）上 `0.85×851 = 723dp`，扣掉 567dp 后图片最大高度只剩约 **156dp**；1:3 的竖长截图最终约 **52×156dp**，宽度仅屏宽的 13%。
- 更关键的是**面板本身也会塌**：默认 `TAB_PAGED` 下 pager 高度取「文本页实测高」与「图片页实测高」的插值（`PickResultPanelLayout.kt:828-836`、`:1332-1346` 的 `Modifier.height(measuredTabPagerHeightPx)`），滑到图片页时面板只有约 `24 + 46 + 图片高 + 58`。→ 用户说的「图片面板默认带高度太低」**同时指出面板变矮与图片变小**，两件事同源。

### 无效补丁（重要）

- 只改 `PickResultPanelLayout.kt:1203-1208` 的 `displayHeight = maxImageSectionHeight.coerceAtLeast(200.dp)` **无效**：图片是 `Modifier.width(...).height(...)` 死尺寸（`PickResultImageSection.kt:155-167`、`:179-192`），容器变高它自己不会变大。
- 必须改的是 `effectivePanelMaxImageHeight`（`FloatBallPickResultContent.kt:251-262`）。改后 `pickResultImageSectionReservedHeight`（`:279-291`）与 `maxImageSectionHeight` 会自动跟随，无需另改。
- **对旧文档的纠正**：`docs/user-feedback-analysis-2026-10-05.md:102` 写的「面板高 = 上限 − IME 高」在当前版本**已不成立**——`FloatBallPickResultContent.kt:384-388` 的注释与代码明确写了「高度上限按整屏可用高度算，键盘让位交给下游各自让一次」，并记录了真机实测原因（MEIZU 21 居中落位下先减一次 IME，卡片只能长到 275dp）。
- 附带发现：`FloatBallPickResultContent.kt:276` 与 `PickResultPanelLayout.kt:818` 两处定义的 `isIntegratedBottomBar` **均无人使用**（全仓仅 2 处命中，都是声明）→ 两种面板样式当前对图片高度**零差异**，所以「用户用哪种样式」不改变本结论。

### 推荐方案（按性价比）

| 方案 | 改动点 | 收益 | 风险 |
| :- | :- | :- | :- |
| **A（小，优先）** 分页态不吃文本/网格预留 | `FloatBallPickResultContent.kt:251-262`：给 `showTabBar` 单独分支，`affordable = maxPanelHeight − panelVerticalPadding − imageSectionFixedChrome`（照抄同文件 :253-256 横屏双列的写法） | 440dpi 机上图片高 156→约 393dp、图片页面板约 284→520dp，**仍 ≤ 0.85 上限** | 滑页时面板高度变化更明显（本来就在变，只是变得更多）；`INTEGRATED` 样式无收益 |
| B（小）折叠态不吃搜索网格预留 | `:202-210` 的 `searchGridReservedHeight` 乘上 `searchExpansionFraction` | 默认网格折叠时白捡 159dp | 展开/折叠网格时图片尺寸跳变，需要过渡 |
| C（中）暴露「图片区最大高度 / 面板上限」设置项 | 打通 `feature/settings` 三层 + 备份编解码 + 跨进程 snapshot（现有 11 项布局设置里**没有任何高度项**，`FloatBallPickPanelLayoutBehaviorSettingsScreen.kt:132-354`） | 用户可控 | 面广；上限超过 0.85 会与贴底/键盘争空间 |
| D（中）改用 `fillMaxWidth + heightIn(max) + ContentScale.Fit` | `PickResultImageSection.kt:155-192` + 传 `maxHeight` 而非死尺寸 | 天然随宽高比变化（可参考 `HistoryContentBlockView.kt:154-167`） | 极端长图会瞬间顶满上限；预留高度公式要同步改 |
| E（中/大）图片页可拖高 / 面板内双击全屏 | 复用 `AuxiliaryCollapseController`（`PickResultPanelLayout.kt:115-182`）或新增预览层 | 不动默认上限 | 与 pager 横滑、点外部关闭的手势冲突 |

**技术前提**：需要把 `showTabBar`（`FloatBallPickResultContent.kt:390-391` 定义）的求值**提到 :251 之前**，否则方案 A 拿不到这个标志。

### 待确认
- 用户设备的分辨率/dpi/系统字体缩放，以及取词面板字号设置（决定「156dp」这类数字）。
- 期望形态是 ①「面板跟着截图变高（仍 ≤0.85）」还是 ②「面板不变、图片区可滚动/双击面板内全屏查看」。
- 是否要顺带做 B（折叠态不扣网格预留）。

---

## 2. 字母索引的位置上下调整：诉求成立，且是真实缺口

### 现状（已证实）

- 「应用索引」= **侧边手势的索引浮层面板**（`OverlayPanelMode.INDEX`），不是 Compose 页面。绘制在 `app/src/main/java/com/slideindex/app/overlay/IndexPanelRenderer.kt`（条 :192-224、气泡 :226-239、应用网格 :241-273），选字母在 `app/src/main/java/com/slideindex/app/gesture/SlideAlongRailSession.kt:65-98`，几何在 `app/src/main/java/com/slideindex/app/gesture/GestureZoneLayout.kt:92-105`；设置页是 `app/src/main/java/com/slideindex/app/ui/LayoutSettingsScreen.kt`（`:80-98` 的「字母索引长度」）。
- **纵向位置是硬编码居中**：
  ```
  GestureZoneLayout.kt:97   val indexH = refHeight * settings.indexHeightFraction
  GestureZoneLayout.kt:98   var top = (refHeight - indexH) / 2f - windowOffsetY
  GestureZoneLayout.kt:99   top = top.coerceSafe(dp(8f) - windowOffsetY, refHeight - indexH - dp(8f) - windowOffsetY)
  ```
  上下各只留 8dp，**没有任何位置参数**。参照高度 `referenceHeight()`（`:348-349`）= `max(layoutHeight, viewHeight)`；宽度 `railVisualWidth()`（`:169`）= `dp(22f)`，再被触钮宽度夹一次（`:96`）。
- 现存的索引相关偏好键**只有两个**（长度与隐藏空字母）：`feature/settings/src/main/java/com/slideindex/app/settings/SettingsPreferenceKeys.kt:80-81`、`SettingsSnapshotReader.kt:198-199`、`EdgeSettingsMutator.kt:451-453`；`indexHeightFraction` 默认 `0.42f`、滑条范围 `0.25f..0.65f`（`AppSettings.kt:21`、`LayoutSettingsScreen.kt:84`）。
- 命中区判定**只看 X**：`GestureZoneLayout.kt:236-244` 的 `isInRailZone` → 整条竖带（含上下留白与 10dp 外扩）都算索引区，`IndexPanelRenderer.kt:148-162` 再命中。→ **位置一变，命中区自动跟随**。
- 「滑块/指示器」其实有两处、都是纯视觉跟随：条内高亮圆（`IndexPanelRenderer.kt:211-216`，画在条上，**必然被手指盖住**）与条外字母气泡（`:318-336`，`cy = selectedLetterCenterY()`，`cx` 在条外 28dp，半径 24dp）。

### 判断：用户更可能指「整条位置可调」（解释①）

- 原话是「**滑块位置**上下调整」，而位置参数正是唯一缺失项；长度已可调（0.25~0.65），位置不可调 → 差异明显。
- 「指示器别挡手指」这类诉求在本仓**已有同类设置覆盖**：`gestureHintFingerOffsetDp`（`AppSettingsSlices.kt:71-72`、`AnimationStyleSelectScreen.kt:150-161`、`values-zh/strings.xml:1464-1465`）。
- 按几何推算，气泡在当前默认下**不会出屏**（居中 + 长度上限 0.65 → 条顶约 ≥17.5% 屏高）。→ 解释②更像是可用性瑕疵而非硬 bug。
- 建议按解释①实现，同时在提问中把②作为备选确认。

### 改动清单（小~中，几何只改一处）

1. **几何**：`GestureZoneLayout.kt:92-105` 把 `top` 的居中改成「居中 + 偏移」或「顶部分数」。气泡/网格/命中区/脏区刷新全部读同一函数 → **自动跟随**。
2. **设置链路（照抄 `indexHeightFraction` 的现成样板）**：`AppSettings.kt:21-23` → `GestureSettings.kt:58-59,147-148` → `SettingsSliceBridges.kt:52-53` → `SettingsSnapshotReader.kt:198-199` → `EdgeSettingsMutator.kt:451-453` → `SettingsRepository.kt:254-255` → `HomeDetailSettingsViewModel.kt:99-112` → `LayoutSettingsScreen.kt:71-124` → `HomeNavEntries.kt:1484-1507`。
3. **实时预览通道（必须照抄，否则松手回跳）**：`OverlayLayoutPreviewStore.kt:8-95` → `AppSettingsOverlayPreviewExtensions.kt:53-59` → `EdgeOverlayHost.kt:389-406` → `OverlayServiceController.kt:79-96` → `SlideIndexAccessibilityService.kt:683-693` → `MainNavContext.kt:289-299`。该 store 里已有「提交值顶住等落盘回流」的防抖，长度滑条就是靠它才不抖（`LayoutSettingsScreen.kt:88-96` 的注释）。
4. **文案**：`values/strings.xml`、`values-zh/strings.xml:508` 附近、`values-ja`、`values-ar` 各加一条。
5. **测试**：`app/src/test` 下**没有任何索引条几何测试**，改 `indexRailRect()` 建议补一个（可直接对 `GestureZoneLayout` 做纯 JVM 测试）。

### 关于「直接拖动索引条」
冲突较大：条内所有 `ACTION_MOVE` 现在都被当成「沿条滑动选字母」（`SlideAlongRailSession.kt:65-89`、`IndexPanelRenderer.kt:114-119`），要拖动就得先做轴锁定仲裁。更稳的做法是**只在设置页的索引预览态允许拖动**（`EdgeGesturePanelRenderer.kt:48` 的 `INDEX_ONLY`），可套用贴边指示条的「拖动 → clamp → 松手持久化」模式：`HistoryFloatService.kt:282-316` + `HistoryFloatHandlePosition.kt:3-17`（含 `UNSET = -1` 默认语义）。

### 顺带发现（已证实，可选修）
- **索引条宽度会被触钮宽度夹成 0**：`GestureZoneLayout.kt:96` 的 `railVisualWidth().coerceAtMost(edgeWidthPx())`，而触钮宽度可低到 0dp（`TriggerHandle.kt:26` 注释「含 0dp」）→ 触钮窄时索引条视觉塌成一条线、字母被屏幕边缘截半、命中带只剩 `w+10dp`。
- **应用网格竖向会溢出屏**：`IndexPanelRenderer.kt:298-316` 的 `coerceSafe` 在 `max < min` 时返回 `min`（`core/common/.../CoerceSafe.kt:3-4`），横屏可用高约 411dp × 每行 72dp 时，同字母应用超过约 5 行就溢出 → 底部点不到。
- 横屏没有单独的索引高度/位置参数；字号只有上限 `dp(11.5f)` 没有下限（`IndexPanelRenderer.kt:203-204`）。
- `app/src/main/java/com/slideindex/app/ui/IndexPanelScreen.kt`（含 `AlphabetRail`）是**死代码**：全仓仅 1 处定义、无任何调用点 → 用户看到的不是它，**别误改**。

### 待确认
- 是解释①（整条可挪）还是②（只挪按住时的高亮/气泡）？
- 位置存「顶部分数」还是「相对居中的偏移」？默认值是否等于当前居中（建议用 `UNSET = -1` 语义，保持老用户观感不变）。
- 横竖屏是否各存一份？是否顺带修上面两个既有缺陷（宽度夹到 0、网格溢出）？

---

## 3. 蜂窝/其它启动器「应用打不开，前几次行后面不行」：一个高置信度根因 + 三个叠加因素

> 这一条与其它四条性质不同：它可能是真 bug。下面把根因与「静默失败点」分开写，**动手前建议先做一次 5 分钟的区分实验**（见文末）。

### 3.0 先排除一个过时前提：跨进程静态失配**不是**本次根因

- `core/common/src/main/java/com/slideindex/app/util/AppProcess.kt:5-19` 明确写了「**现架构（已从多进程回退）**」：只剩「默认进程（设置 UI + 无障碍服务 + 全部浮层/面板）」与 `:engine`。历史上的 `:overlay` 已被合并删除。
- 所以 `RingLauncherOverlayWindow.kt:96-101`、`WidgetPickerTrampoline.kt:104`、`TrampolineResultPort.kt:11` 里「跨进程静态读不到」的注释**已过时**（顺带：`RingLauncherOverlayWindow.selfHealAfterExternalActivity()` 是给已不存在的问题写的自愈）。
- **同时**，仓库里那份 ANR 报告（`.tmp/anr/summary.md`，v62/1.25.0 时代，4 次 `Input dispatching timed out`，根因是主线程同步 `getApplicationIcon`）**也不能当作 1.41.0 的证据**——项目后来已建后台图标缓存并把那次事故写进了注释（`OverlayIconLoader.kt:11-25`）。

### 3.1 高置信度根因：蜂窝/环形/全息的「窗口还活着吗」判据会永久骗人

**机制（已证实）**：无障碍实例被系统重建时，该实例窗口 token 上的窗口会被系统**整片摘掉**，而客户端**收不到 `onViewDetachedFromWindow`** —— 于是连 `View.isAttachedToWindow` 都还是 `true`。这一点在 `app/src/main/java/com/slideindex/app/overlay/OverlaySidePanelHost.kt:105-110` 的注释里有真机实测记录（「`dumpsys window` 里已经没有面板窗，`isAttachedToWindow` 仍为 true」），并在 `docs/capsule-refactor-plan.md:1410-1462`（§0.16.26）有完整事故复盘，原文结论就是「**只判 `View.isAttachedToWindow` 也不够（客户端会撒谎）**，必须判『我建窗时依赖的那一代无障碍实例还在不在』」，且当时的表现是**「面板永远不出现，只能强停 App / 重启手机」**。

**修法存在，但只落到了一个宿主**：
- 代次机制本身：`app/src/main/java/com/slideindex/app/service/SlideIndexAccessibilityService.kt:156-159`（`instanceGeneration` / `overlayHostGeneration()`）、递增在 `:948`、`:1054`。
- **唯一使用者**：`OverlaySidePanelHost.kt:103`（`hasLivePanelWindow`）、`:112`（`attachedGeneration`）、`:121-132`（`dropStalePanelShell` 丢弃陈旧壳子）、`:349`。全仓 `overlayHostGeneration` 只有这 5 处命中（已核实）。
- **蜂窝 / 环形 / 全息一个都没接**：
  - `app/src/main/java/com/slideindex/app/overlay/HoneycombAppPickerOverlayWindow.kt:29,37`（静态 `controller` + `isShowing = controller?.isVisible == true`），`HoneycombOverlayController.java` 的 `isVisible()` 是 `attached && view != null`；
  - `app/src/main/java/com/slideindex/app/overlay/ringlauncher/RingLauncherOverlayController.kt:45,50`（`isVisible(): Boolean = attached && view != null`），静态入口 `RingLauncherOverlayWindow.kt:52,62`；
  - `app/src/main/java/com/slideindex/app/overlay/holographic/HolographicLauncherOverlayWindow.kt:24,28`。
  - 快捷启动器**机制不同**：它没有自己的窗口（挂在共享的 `EdgeGestureOverlayView` 上），其 `isQuickLauncherVisible()` 判的是 `gestureSession().panelMode() == QUICK_LAUNCHER`（`QuickLauncherOverlayController.kt:162-163`），**不是** `attached && view` 形态 → 它**大概率不受 3.1 影响**，本条的「其它启动器」主要指蜂窝/环形/全息（**已核实，与子调查的假设不同，已修正**）。
- **且没有任何路径在无障碍实例重建时清理它们**：`SlideIndexAccessibilityService.onUnbind`（`:1030-1048`）只 `stopOverlayHostLease()`（`:1033`）+ `edgeOverlayHost?.stop()`（`:1036`），而 `EdgeOverlayHost.teardownOverlayWindows()`（`app/src/main/java/com/slideindex/app/overlay/EdgeOverlayHost.kt:188-203`）只停悬浮球/边角/轮换监控/`overlayManager`，**不调用 `GlobalOverlayDismissHelper.dismissAllPanels()`** —— 而该 helper 的 `:48-49` **明明写着** `HoneycombAppPickerOverlayWindow.dismiss()` 与 `RingLauncherOverlayWindow.dismiss()`（它只在 `OverlayLocaleCoordinator.kt:27`、`SlideIndexAccessibilityWatchdog.kt:31,58`、`SlideIndexAccessibilityService.kt:459` 这些路径上被调用）。⇒ `controller` 永不置空、`isShowing` 永为 true、`appContext` 还是那个已被销毁的 Service，于是**窗口再也开不出来，且日志与 toast 都是零**。
  - 顺带：`GlobalOverlayDismissHelper` 的清理清单里**根本没有全息启动器**（`:41-56` 没有 `HolographicLauncherOverlayWindow`）→ 它也是「没人清」的一员。
- Browse/pin 模式会把这个窗口期放大：`HoneycombAppPickerOverlayWindow.kt:119` 的 `persistAfterSessionEnd`（会话结束不 dismiss）、`RingLauncherOverlayWindow.kt` 的 `persistAfterPin`。

**为什么与用户描述高度吻合**：静态状态只在**进程存活**期间陈旧 → 前几次（实例没有被系统重建）都正常；一旦发生重建，从那一刻起所有启动器永久打不开，**只有强停 App 才能恢复**。用户说的「非常频繁、前面几次可以后面就不行」正是这个形状。

> **文档级佐证**：`docs/capsule-refactor-plan.md:1412-1413` 记录的用户原话是「收纳面板从**任何入口**都打不开 —— 点了没有任何反应，也没有 toast」，`:1413` 还特别写了「**同一时刻搜索面板正常**」，`:1457` 写「修复前同一条路径的表现是『面板永远不出现，**只能强停 App / 重启手机**』」——与本次反馈的现象描述几乎逐字一致，只是换成了启动器。
>
> 更值得注意的是该文档的遗留清单（`:1459-1462`）只点了 `SearchPanelOverlayWindow` 与 `FloatBallPickResultPanel`，**四个启动器从未进入过视野**——本次反馈很可能就是这条遗留问题的下一步。

### 3.2 叠加因素（**同时存在，各自都会造成「点了没反应」**）

**A. 后台启动（BAL）被系统静默拦截，代码既不判结果也不提示**
- 最常用路径是**裸 `startActivity`**：`app/src/main/java/com/slideindex/app/util/FreeWindowLauncher.kt:49-52`（`fullscreen || !freeWindowEnabled` 时直接 `context.startActivity(intent)`）。Android 10+ 的后台启动限制在**被拦截时不抛异常**，所以连 `runCatching` 都抓不到；小窗分支虽然包了 `runCatching`，失败也只是 `Log.e`（`:70-74`、`:111-116`、`:162-166`）。
- `AppLaunchPort.launch` 返回 `Unit`（`app/src/main/java/com/slideindex/app/di/AppPortsModule.kt:146-153`），而 `AppRepository.launchApp` 无论如何都 `return true`（`feature/apps/src/main/java/com/slideindex/app/data/AppRepository.kt:354-359`）→ **上层永远以为成功**。

**B. 冰箱（冻结/暂停）成员在启动器里点击必然静默失败**
- 两处 `?: return false` 且**无日志**：`app/src/main/java/com/slideindex/app/gesture/executor/ActionExecutorLaunch.kt:94-96`、`AppRepository.kt:354-356`。
- `PAUSED = pm suspend` 的应用仍 enabled，但 launcher 查询会把它过滤掉 → `getLaunchIntentForPackage` 返回 null；`FROZEN = pm disable` 的应用在 `AppRepository.kt:427-438` 的 `queryAppInfo`（`getApplicationInfo(pkg, 0)`，**未传 `MATCH_DISABLED_COMPONENTS`**，而同文件 `:313-322`、`:258-265` 都传了）会抛 `NameNotFoundException` → null。
- **对照证据（很好用）**：冰箱自己的 UI 走的是 `app/src/main/java/com/slideindex/app/freezer/FreezerOperations.kt:184-244` 的 `launchAndRestore` —— **先解冻/解挂起、失败还会弹 toast、并带 `MATCH_DEFAULT_ONLY` 兜底**；而四个启动器都只调 `appRepository.launchApp`，**没有这一步恢复、也没有兜底**。→ 「冰箱面板里点同一个应用会弹提示，启动器里点完全不弹」本身就是判据。

**C. 悬浮球触摸窗被置 `GONE` 后没有复位路径（蜂窝路径独有；悬浮球被遮挡时也顺带影响所有启动器）**
- `app/src/main/java/com/slideindex/app/overlay/FloatBallOverlay.kt:1896-1897` 置 `GONE`，唯一复位者是 `restoreChromeAfterRingLauncher()`（`:960-961`），而它**只被圆环启动器调用**；`syncBallTouchWindowLayout`（`:1587-1623`）**不复位 visibility**（对比 `syncLineTouchWindowLayout:1658` 是有复位的）。
- 命中时的表现是「球还在但点不动 → 所有经悬浮球触发的启动器都打不开」（**推测**：需真机确认）。

### 3.3 静默失败点清单（都是「点了没反应且无提示」）
| 位置 | 行为 |
| :- | :- |
| `ActionExecutorLaunch.kt:94-96` | 缓存未命中 + `lookupApp` 失败 → `return false`，**无日志** |
| `AppRepository.kt:354-356` | `getLaunchIntentForPackage() ?: return false`（暂停/冻结应用必为 null） |
| `AppRepository.kt:427-438` | `queryAppInfo` 未传 `MATCH_DISABLED_COMPONENTS`（冻结应用抛异常 → null） |
| `FreeWindowLauncher.kt:49-52` | **裸 `startActivity`**，BAL 拦截时系统级静默丢弃 |
| `FreeWindowLauncher.kt:70-74,111-116,162-166` | `runCatching{...}.onFailure{Log.e}` 只打日志 |
| `FreeWindowLauncher.kt:177-204` | 米窗广播「即发即忘」，发出即 `return true`，无法校验是否真开出小窗（注释自陈） |
| `HoneycombAppPickerOverlayWindow.kt:67-75` | 列表空 / 无障碍未启用 → `return false`，仅 `Log.w` |
| `HolographicLauncherOverlayWindow.kt:46-67` | 无障碍/宿主/repo/`getCachedApps()` 空 → `return false`，仅 `Log.w`（注意：**这是全息「整个打不开」的独立原因**） |
| `HoneycombOverlayController.java:136-142` | `addView` 失败 → `Log.e` + false |
| `RingLauncherOverlayController.kt:146-150` | `addView` 失败 → **连日志都没有** |
| `RingLauncherOverlayController.kt:243-252` | `bringToFrontLocked` 的 `removeViewImmediate + addView` 包在 `runCatching`，失败则视图被摘掉且 `attached`/`view` 不复位 → 潜在幽灵窗（未证实） |

### 3.4 已排除（避免重复劳动）
- **无请求码 / PendingIntent / `startActivityForResult` 上限**：全仓 `startActivityForResult` 只有 `service/ScreenCapturePermissionActivity.kt:19`（录屏授权），与启动无关。
- **无启动节流 / 防连点 / `isLaunching` / `lastLaunchTime` / 冷却**：`debounce|cooldown|throttle` 的命中全在摇一摇、翻转静音、截图节流、`NudgeThrottle`、AI 翻页冷却上，**启动路径一条都没有**；`QuickLauncherPickResolver.kt` 是纯命中测试，唯一「延迟」是 `quickLauncherLaunchEndDeferMs`（UI 动画用）。
- **蜂窝的数据映射文件不含启动/缓存逻辑**：`HoneycombTargetResolver.kt`、`HoneycombRuntimeTarget.kt`、`HoneycombLauncherItems.kt` 是纯映射。
- **包可见性/权限不是原因**：`app/src/main/AndroidManifest.xml:52-54` 有 `QUERY_ALL_PACKAGES`，`:64-113` 声明了 `MAIN/LAUNCHER` 等 `<queries>`。
- **`QuickLauncherAddTrampoline.active` 卡死这条路不成立**：`QuickLauncherAddTrampoline.launch()` 全仓**零调用点**（只剩 `isActive()` 被读），所以 `OverlayTrampolineGuard.blocksOverlayPresentationTouch()` 不会被它卡住。
- **未见协程/Handler 标志位卡死**（`HoneycombOverlayController.moveFrameScheduled` 等都有配对复位）。

### 3.5 动手前的最小区分实验（**5 分钟，能一刀切开根因**）
1. **先分「启动器打不开」还是「应用启动失败」**：让用户用**无障碍手势**（不经过悬浮球）触发蜂窝/环形。
   - 手势也打不开 → 走 3.1（陈旧壳子）或 3.2A/B；
   - 手势能开、只有悬浮球不行 → 走 3.2C（触摸窗 `GONE`）。
2. **坐实 3.1**：按 `docs/capsule-refactor-plan.md:1451-1457` 的原样复现法——摘掉本应用的无障碍服务 1.5s 再写回，随后立刻用手势开蜂窝。修复前表现：窗口永不出现，而 `isShowing` 仍为 true。加一行日志打 `controller.isVisible` + `overlayHostGeneration()` 即可确认。
3. **验 3.2A**：`adb logcat | grep -iE "Background activity start|not allowed|abort"`；或在 `FreeWindowLauncher.kt:49-52` 前后加 `EdgeDiag.log`（`app/src/main/java/com/slideindex/app/diagnostic/EdgeDiag.kt`，debug 构建自动生效）。「用户点了但 logcat 里连 WindowManager 记录都没有」= 被系统吞掉。用户已内建一键导出 `EdgeDiag.exportText`。
4. **验 3.2B**：看冰箱列表与蜂窝/环形槽位是否有交集 → `adb shell dumpsys package <pkg> | grep -E "enabled=|suspended="`；再用「冰箱面板里点同一应用会弹提示、启动器里不弹」做对照。
5. **验 3.2C**：`adb shell dumpsys window windows | grep -A3 slideindex` 看触摸窗的 `mViewVisibility`（`8` = GONE）。

### 待确认
- 设备型号/系统（`.tmp/anr/summary.md` 那台是 MEIZU 21 / Android 16）。
- 是否用过「shell 命令面板 / 命令编辑器」（涉及 `ShellCommandEditorTrampoline` 的 `active` 标志，`onDestroy` 不走时会永久 gate 住 `resumeAllEdgeOverlays()`）。
- 复现时是否伴随「整屏手势都失灵几秒」——若是，则更偏主线程卡顿而非启动链路。

---

## 4. 全息启动器改为自添加应用：现状确为「全部应用 + 黑名单」，改造很干净

### 现状（已证实）
- 唯一应用来源是**全量启动器列表**再排除黑名单：`app/src/main/java/com/slideindex/app/overlay/holographic/HolographicLauncherOverlayWindow.kt:111-127`（`:118` 取 `appRepository.getCachedApps()`、`:119` 排除自身包名与 `hiddenAppPackages`）。
- 设置里**只有黑名单字段**，没有白名单/条目列表：`feature/settings/src/main/java/com/slideindex/app/settings/HolographicLauncherSettings.kt:5-13`（`:9` `hiddenAppPackages: Set<String>`）；设置页只挂了「隐藏应用」入口（`app/src/main/java/com/slideindex/app/ui/HolographicLauncherSettingsScreen.kt:205-222` + `app/src/main/java/com/slideindex/app/ui/navigation/HolographicLauncherNavEntries.kt:30-41`）。**无任何开关、无 TODO**。
- 顺带一个用户可见的怪现象：`app/src/main/java/com/slideindex/app/overlay/holographic/Ball3DView.kt:117` 是 `apps.shuffled(Random).take(60)` → 全量模式下**每次打开随机抽 60 个**（用户会觉得「每次看到的不一样、有些应用永远找不到」）。**已证实**。
- 空列表会被两处 early-return 拦掉：`HolographicLauncherOverlayWindow.kt:64-67`、`HolographicLauncherOverlayController.kt:27` → `Ball3DView.kt:145-150` 的空态文案（`holographic_no_apps_loaded` / `holographic_check_settings_hint`）**实际不可达**（推测：死代码）。这一点对改造**很重要**：白名单默认空 = 「点了完全没反应」，连引导文案都看不到。

### 改造可行性：高（架构干净），工作量中（约 8–12 个文件）
- **只有 1 处**真正依赖「全部应用」：`HolographicLauncherOverlayWindow.kt:118`（+ `:119` 的黑名单过滤）。渲染/命中/旋转链路（`Ball3DView` / `HolographicLauncherOverlayView` / `HolographicLauncherOverlayController` / `HolographicLauncherBackgroundView`）只消费 `List<HolographicLauncherApp>`，与数据来源无关。
- **可直接照抄的模板**（蜂窝/快捷启动器**已经是自添加**）：
  - 数据模型与编解码：`core/gesture/src/main/java/com/slideindex/app/launcher/QuickLauncherItem.kt:25-94`、`:127-450`（`encodeAll/decodeAll` `:399-409`）。
  - 持久化链路：`SettingsPreferenceKeys.kt:127`（`HONEYCOMB_LAUNCHER`）→ `OverlaySettingsMutator.kt:582-586` → `SettingsRepository.kt:455-456` → `SettingsSnapshotReader.kt:256-258`。
  - 增删去重：`ExtensionSettingsViewModel.kt:193-215`。
  - 选择器 UI：`HoneycombLauncherAddPickerScreen.kt:48-231`（三 tab，带已选反显 `:73-75`）。
  - **最省事的白名单 UI**：`app/src/main/java/com/slideindex/app/ui/HiddenAppsScreen.kt:33-152` —— 「已选列表可移除（`:94-108`）+ 下方可搜索全量一键加入（`:109-150`）」，语义与白名单**只差一个正负号**，而全息现在正在复用它。加个 `mode: Whitelist|Blacklist` 参数即可。
  - 导航：`HoneycombLauncherNavEntries.kt:28-197`；`AppNavKey.kt:390-391`。

### 改动清单（按层）
- **数据层**：`HolographicLauncherSettings.kt:5-13` 新增字段（推荐 `items: List<QuickLauncherItem>`，与蜂窝同构；极简版 `appPackages: Set<String>`）；`:36-55` 的 `fromPreferences` 增加解码；`HolographicLauncherOverlayWindow.kt:111-127` 重写 `resolveApps()`，删掉 `:118`/`:119`。
- **持久化**：`SettingsPreferenceKeys.kt:265-271` 新增键；`OverlaySettingsMutator.kt:1805-1815` 附近新增 set/增删；`SettingsRepository.kt:509-531` 透传；`SettingsSnapshotReader.kt:266`（或放进 `fromPreferences`）；`ExtensionSettingsViewModel.kt:119-148` 新增 toggle。
- **⚠️ 多进程同步（漏一处 = overlay 进程静默读默认值，这是本仓最典型的坑）**：`AppSettingsSlices.kt:118`、`GestureSettings.kt:87` 与 `:175`、`SettingsSliceBridges.kt:81`、`AppSettings.kt:213`。
- **UI/导航/文案**：`HolographicLauncherSettingsScreen.kt:205-222,227-247`；`HolographicLauncherNavEntries.kt:12-42`；`AppNavKey.kt:390-391`；四套 `strings.xml`（`values:2201/2211/2212`、`values-zh:860/870/871`、`values-ja:856/866/867`、`values-ar:834/844/845`）+ `values-zh/plurals.xml:127` 等 plurals。
- **渲染**：`Ball3DView.kt:111-130`（去掉 `shuffled(Random)`、重新决定 `take(60)` 上限）；`Ball3DView.kt:145-150` 的空态要么放行空列表、要么另给 toast/跳设置引导。

### 风险与待确认（**最大的是默认值**）
1. **默认空 = 老用户升级后球面全空，且「点了没反应」**（两处 early-return 会把引导文案也一起吃掉）。必须先定：默认空 + 首次引导？还是「白名单为空时回落到旧的黑名单行为」这种过渡期设计？
2. **不要做「全量快照」迁移**：本机规模约 300+ 包（`AppRepository.kt:388` 附近的注释提到实测 319 个带启动图标的包），而 `QuickLauncherItemCodec.encodeAll` 会把整表拼成**单个字符串**（`QuickLauncherItem.kt:399-400`）；且 `take(60)` 只会显示 60 个。得不偿失。
3. **黑名单字段去留**未定：保留（白名单 ∩ 非黑名单，最兼容）还是废弃？两者并存会让 UI 出现「已添加 / 已隐藏」两套概念。
4. **`take(60)` 且无分页**：全息链路没有分页、没有字母索引、没有排序（已证实），超过 60 个只能靠透视缩放，可点性需实测。
5. **未安装/被卸载应用**需要「未安装」标记（蜂窝有先例）。
6. **测试空白**：`feature/settings/src/test`、`core/gesture/src/test` 下**没有任何全息相关测试**，改动没有回归网；建议至少为新的编解码/过滤逻辑补一个单测（照 `QuickLauncherItemCodecTest.kt`）。

---

## 5. 快速启动器非编辑态的 +/−：确实常驻，去掉容易但要先定替代入口

### 现状（已证实）
- 悬浮面板底部那条胶囊工具栏是 **Canvas 手绘**（不是 Compose）：`app/src/main/java/com/slideindex/app/overlay/QuickLauncherPanelToolbar.kt`，画在面板正下方（`:123` `top = panelRect.bottom + toolbarGap`）。
- 两个按钮的语义（`:15` 枚举）：
  - 左「**+**」= `ADD` → 打开「添加快捷项」Sheet（`:242-245`）；
  - 右「**−**」= `EDIT` → **编辑模式开关，不是删除**：非编辑态画「−」、编辑态画「✓」（`:322`），无障碍文案是 `widget_panel_edit_mode`＝"编辑模式"（`:371-375`）；
  - 多面板时中间还有一枚「面板名 ⇄」= `SWITCH`（`:178-180`）。
- **显示条件完全不看编辑态**：`:67-70`
  ```
  shouldShowToolbar() = host.isPanelReady() && controller.displayItems().isNotEmpty() && !host.isAddDialogShowing()
  ```
  → 非编辑态必然出现 + 与 −。真正的「−」删除是**编辑态**下每格左上角的红圈减号（`:190-218`，两处 `if (!controller.editMode) return`）。
- **面板内进入编辑态的唯一入口就是这枚「−」**：全仓 `setEditMode(true)` 只有 `:249-252` 一处（`QuickLauncherOverlayController.kt:498-501` 是退出）。
- 兜底入口存在且独立：设置 → 扩展中心 → 快速启动器 → 应用内编辑器（`LayoutSettingsScreen.kt:184-208` → `ExtensionHubNavEntries.kt:54` → `QuickLauncherNavEntries.kt:31-40` → `QuickLauncherEditorScreen.kt:160`），该页自带右侧 +/−、红点删除、拖拽排序、面板增删（`QuickLauncherGridEditorChrome.kt:135-165`、`QuickLauncherPanelManagementSection.kt:129-189`）。→ **去掉面板工具栏后，用户仍能编辑快捷启动器，只是不能在面板内就地编辑**。
- 相关文案/图标是硬编码字形：`:290-296`（"+")、`:322`（"−"）。多语言键：`quick_launcher_add`、`widget_panel_edit_mode`、`cd_action_confirm`、`quick_launcher_panel_switch`。

### 长按占用现状（**这是本条的关键约束**）
| 场景 | 现行为 | 位置 |
| :- | :- | :- |
| 非编辑态长按 APP/快捷方式图标 | **启动策略长按**（全屏↔小窗互换），仅当开了小窗且策略为第 3/4 档 | `QuickLauncherPickResolver.kt:201-235`、`AppLaunchPolicy.kt:6-14,47-51` |
| 非编辑态按住文件夹格子 | 180ms **直接展开文件夹** | `QuickLauncherPickResolver.kt:62-77` |
| 编辑态长按格子 | 180ms 后**开始拖拽排序/合并** | `QuickLauncherPanelManagementHandler.kt:122-143` |
| 非编辑态按面板**空白处** | 松手**关闭整个面板**（无长按功能） | `QuickLauncherTouchHandler.kt:294-302` |
| 非编辑态按**面板外** | `ACTION_DOWN` 立刻关面板 | `QuickLauncherTouchHandler.kt:119-123` |

→ **真正空闲的只有「面板空白处」**，但它当前短按会关面板，需要加一个「本次已被长按消费」的抑制位。

### 可选形态
1. **去掉 +/−，改「长按面板空白处进入编辑」**（最贴近用户提议）：改 `shouldShowToolbar()` + 在空白 UP 分支加抑制位。
2. **加「显示操作工具栏」设置项**（不动默认行为，给用户自己关）：仓库有现成同类先例——圆环启动器的 `fv_ring_launcher_show_toolbar`（`SettingsPreferenceKeys.kt:178` + `RingLauncherSettingsScreen.kt:364-365` + `RingLauncherOverlayView.kt:367-371`）。快捷启动器的 `QuickLauncherDisplaySettings.kt:6-11` 目前只有背景不透明度/图标大小/形状/模糊四项，**没有**任何工具栏相关键（已证实）。
3. **只隐藏 ADD 与 EDIT、保留 ⇄**：改动最小，但底部会留一条只有切换键的窄条，需产品确认。

### 风险（改 `shouldShowToolbar()` 时要一起看的）
- **`combinedContentRect()` 会缩小约 44dp**（工具栏 36 + 间距 8），它被用于触摸 X 投影与「是否可选中」（`QuickLauncherTouchHandler.kt:19-20`、`QuickLauncherPickResolver.kt:85-90`）、局部重绘脏区（`QuickLauncherOverlayController.kt:650`）、进入动画裁剪区（`EdgeGesturePanelRenderer.kt:65-70`）→ **动画与「下方触摸是否算面板内」都会变**，必须一并回归。
- 管理触摸有前置门槛 `if (!toolbar.shouldShowToolbar()) return false`（`QuickLauncherPanelManagementHandler.kt:82`）→ 直接改 `shouldShowToolbar()` 会连带禁用该函数里的其它逻辑，需逐条确认。
- 工具栏命中区有 **10dp 外扩**（`QuickLauncherPanelToolbar.kt:333-338`）→ 若「保留一条空区域用来长按」，这段外扩会抢走面板底部边缘/相邻格子的触摸；且 `QuickLauncherPanelManagementHandler.kt:92-103` 在 DOWN 阶段就先判工具栏并 `return true`。
- 无障碍：工具栏会注册虚拟节点（`:355-382`，含"编辑模式"）→ 去掉后读屏用户失去入口，若要改长按建议同时补一条无障碍动作。
- 顺带发现：面板在用户没配置过时会被**默认塞进前 12 个应用**（`QuickLauncherPanelController.kt:120-127` → `QuickLauncherDefaults.kt:6-9` 的 `fromApps`/`DEFAULT_ITEM_COUNT = 12`）——与第 4 条「默认用全部应用」是同一类观感问题，可作为同批产品讨论的输入。

### 待确认
- 用户说的到底是**悬浮面板底部**（本报告判断）还是**应用内编辑器右侧**的 +/−（后者是右侧竖排，与「下方」不符，但字面"页面"有歧义）？
- 替代入口选哪种：长按空白 / 设置项开关（推荐先做 2 的开关形态，风险最低）/ 两者都做？
- 「+ 添加」是否也要在非编辑态去掉（去掉后连"就地添加"都没了）？多面板的 ⇄ 是否保留？

---

## 6. 交叉观察与建议排序

1. **第 1 + 第 2 条建议同批做**：都在「应用索引 / 取词面板」的布局与设置层，都在 `feature/settings` 的既有样板（`indexHeightFraction` / 取词面板 11 项布局设置）上追加，且都不破现有约束（0.85 上限、0.25~0.65 长度区间）。第 1 条只改一处分支，第 2 条几何只改一处，剩余是照抄样板。
2. **第 2 条与「顺带发现」可以合并评审**：`indexRailRect()` 的改动天然要重新审视 :99 的 8dp 夹取（现在不含 insets/挖孔）。既然要动这一行，把「宽度被触钮夹成 0」和「网格竖向溢出屏」一起评估更划算。
3. **第 4 条先定默认值再动手**：其它都能照抄蜂窝，唯一的真决策是「白名单为空时怎么办」——因为空列表会被两处 early-return 变成「点了没反应」，连引导文案都看不到。建议二选一：① 默认空 + 首次进入给引导；② 保留黑名单做过渡（白名单为空时回落到旧行为），下一版本再切。
4. **第 5 条建议先做「设置项开关」而不是直接去掉**：开关形态有圆环启动器的现成先例、改动面可控、不动默认行为，也不用立刻解决「长按已被占用」和「combinedContentRect 缩小」这两个连带问题；等用户确认替代入口后再考虑长按方案。
5. **第 3 条优先级最高，但第一步是「区分实验」而不是改代码**：3.1 的陈旧壳子根因置信度高（仓库自己修过同类问题、机制有真机实测记录、判据只落到一个宿主），代价也只是「照 `OverlaySidePanelHost` 的写法逐个启动器补代次判据」；但它是本批里唯一涉及「永久性打不开、只能强停 App」的条目，且还有 3.2 的 A/B/C 三个叠加因素同样会造成「点了没反应」。**建议先做 3.5 的第 1 步（5 分钟）**，确认是「启动器开不出来」还是「应用启动被拒」，再决定改哪一层。
6. **顺手可修（低风险、各自独立）**：
   - `HolographicLauncherOverlayWindow.kt:111-127` 对全量每个包同步解析图标、随后 `Ball3DView.kt:117` 只留 60 个 → 纯浪费（且是主线程同步解析，参考 `.tmp/anr/summary.md` 的前科），顺手改成「先抽样再解析」或走 `peekLaunchIconDrawable`。
   - `Ball3DView.kt:117` 的 `shuffled(Random)` → 用户「每次看到的应用不一样」的直接原因。
   - `AppRepository.kt:427-438` 的 `queryAppInfo` 未传 `MATCH_DISABLED_COMPONENTS`（同文件 `:313-322`、`:258-265` 都传了）→ 冻结应用查不到信息，正是 3.2B 的一环。
   - `GlobalOverlayDismissHelper.kt:41-56` 的清理清单里**漏了全息启动器**（有蜂窝、圆环，没有 `HolographicLauncherOverlayWindow`）；且 `EdgeOverlayHost.teardownOverlayWindows()`（`:188-203`）在无障碍实例销毁时**没有**调用这个 helper → 这是 3.1「为什么状态没人清」的补丁点。
   - `docs/capsule-refactor-plan.md:1459-1462` 自己登记的遗留：`SearchPanelOverlayWindow` 与 `FloatBallPickResultPanel` 也还在用会撒谎的 `isAttachedToWindow` 判据，同样该接代次判据（与 3.1 是同一件事，建议一并处理以免第二批反馈）。
   - `FloatBallPickResultContent.kt:276` / `PickResultPanelLayout.kt:818` 的 `isIntegratedBottomBar` 死变量（两处都是声明后无人使用）。
   - `PickResultPanelLayout.kt:292,323,339` 用单个 `PANEL_VERTICAL_PADDING`，而实际布局是 `×2`（`:988`）→ 外层估算与真实高度差 12dp（推测无用户可见影响，但改公式时别踩）。
   - `app/src/main/java/com/slideindex/app/ui/IndexPanelScreen.kt` 整文件死代码（含 `AlphabetRail`）。
   - 过时注释：`RingLauncherOverlayWindow.kt:96-111`（自愈注释）、`WidgetPickerTrampoline.kt:104`、`TrampolineResultPort.kt:11` 仍按已不存在的 `:overlay` 进程描述行为。

## 附：动手前需确认的清单

- [ ] **第 3 条（优先）**：先用 3.5 第 1 步区分「启动器开不出来」还是「应用启动被拒」；再确认是否开启小窗与启动方式档位、哪个启动器复现、是否用过 shell 命令面板、复现时是否伴随整屏手势失灵几秒。**区分实验之前不建议改代码。**
- [ ] **第 1 条**：用户设备的 dpi/字体缩放与取词面板字号；期望是「面板跟着变高」还是「图片区可滚动/面板内全屏」；是否顺带做「折叠态不扣网格预留」。
- [ ] **第 2 条**：是「整条索引可挪」还是「只挪按住时的高亮/气泡」；偏移量存顶部分数还是居中偏移；横竖屏是否各存一份；是否顺带修宽度夹到 0 / 网格溢出。
- [ ] **第 4 条**：白名单为空时的默认行为（空 + 引导 / 回落到旧黑名单）；`hiddenAppPackages` 保留还是废弃；是否需要支持快捷方式/动作（对齐蜂窝）还是只做应用；`take(60)` 上限是否调整。
- [ ] **第 5 条**：指的是悬浮面板还是编辑器页面；替代入口选「长按空白」还是「设置项开关」；「+」与多面板「⇄」是否保留。

---

## 7. 本轮实际落地（第 1/2/4/5 条已实现，第 3 条按用户要求未动）

> 用户指示「一二四五全做，一次做完」，且明确「第三条在我手机上没复现」→ 第 3 条本轮**不改代码**，仅保留 §3 的分析与取证步骤。
> 验证方式：`.\gradlew.bat :app:compileFullDebugKotlin --offline` 通过（Kotlin 主代码零错误）；改动文件行尾经 `scripts/normalize-eol.py --check` 全部为 LF。
> ⚠️ `feature:settings:testDebugUnitTest` 有**既有的、与本次无关的**编译错误（`feature/settings/src/test/java/com/slideindex/app/settings/macro/MacroListStateTest.kt:101-107`，本次未改动该文件），所以新加的 `HolographicLauncherAppPackagesCodecTest` 暂时跑不起来。

### 第 1 条：取词面板分页态的图片高度预算

- `app/src/main/java/com/slideindex/app/overlay/pickresult/FloatBallPickResultContent.kt`：新增 `tabPagedImageOwnsPage` 判定，分页样式下图片独占一页时改用
  `maxPanelHeight − 面板上下 padding − 分页头(46dp) − 图片区自身 chrome(58dp)`
  作为上限（不再扣文本区 220dp 最小正文 + 搜索网格 159dp + 分隔线 10dp）。
- 关键取舍：**必须减掉分页头**，否则「图片 + 自身 chrome + Tab 头 + padding」会超出 0.85 面板上限而被 `clipToBounds()` 裁掉。
- 未改 `INTEGRATED_BOTTOM_BAR` 样式（同屏堆叠没有"独占一页"这回事），也未动 0.85/0.92/0.70 三个上限。

### 第 2 条：字母索引纵向位置 + 调节预览

- **几何**：`app/src/main/java/com/slideindex/app/gesture/GestureZoneLayout.kt` 的 `indexRailRect()` 从"死居中"改为
  `top = 8dp + (可用高度 − 条长 − 16dp) × indexVerticalPositionFraction`，默认 0.5 时化简结果与旧代码**完全相等**（老用户观感不变）。气泡 / 应用网格 / 命中区（`isInRailZone` 只看 X）/ 脏区刷新都读同一个函数，自动跟随。
- **数据层**：`AppSettings`、`GestureSettings`（含 `toMinimalAppSettings` 桥接）、`SettingsPreferenceKeys.INDEX_VERTICAL_POSITION`、`SettingsSnapshotReader`（读时 `coerceIn(0f,1f)`）、`SettingsSliceBridges`、`EdgeSettingsMutator`、`SettingsRepository`、`HomeDetailSettingsViewModel`。
- **调节预览**：完整复刻 `indexHeightFraction` 的通道 —— `OverlayLayoutPreviewStore` → `AppSettingsOverlayPreviewExtensions.withOverlayLayoutPreview()` → `EdgeOverlayHost` → `OverlayServiceController` → `SlideIndexAccessibilityService` → `MainNavContext`；页面侧 `LayoutSettingsScreen` 的新滑条与长度滑条一样设 `commitOnFinish + triggersLayoutPreview`，拖动期只走预览、松手才落盘一次。
- **自主决策（两处，需你复核）**：
  1. **滑条语义取「0% = 顶部、100% = 底部」**（右滑向下）。备选是反向映射，视觉直觉上两种都讲得通。
  2. **两个滑条共用一个预览会话时，松手只提交「最近拖过的那一个」**：新增 `OverlayLayoutPreviewStore.IndexSlider` 记录最后触及的滑条与 `commitIndexSliderPreviews()`。不这样做的话，长度或位置任一松手都会把**两个**"待确认"值同时顶 2 秒（`COMMIT_TTL_MS`），造成另一项短暂显示错值。
- 文案：四套 `strings.xml` 新增 `index_vertical_position` / `_desc`（`%` 已按 Android 资源要求转义为 `%%`）。

### 第 4 条：全息启动器改为「自己添加应用」

- **数据层**：`HolographicLauncherSettings.appPackages: List<String>`（有序）+ 新编解码 `HolographicLauncherAppPackagesCodec`。
  - 刻意**不用 `Set` 直接存**：Set 会打乱顺序，而球面按列表顺序用黄金角铺点 —— 顺序一变用户每次看到的位置都不同。因此沿用仓库既有做法（`ExcludedAppScopesCodec` 等）编码成**单元素 Set 里的 RS 分隔串**。
  - `MAX_APP_PACKAGES = 500`、`BALLOON_APP_LIMIT = 60`；空列表写成"没有这个偏好值"，以便区分「未配置」与「配置成空」。
- **渲染**：`HolographicLauncherOverlayWindow.resolveApps()`
  - 已添加过 → **只显示这一份，且不再套用 `hiddenAppPackages`**（用户已显式挑过）；未添加过 → 回落旧行为「全部应用 − 隐藏列表」。
  - 顺手修了两个既有问题：`Ball3DView.setApps()` 去掉 `shuffled(Random)`（这是用户"每次看到的应用都不一样"的直接原因），回落分支改为按 `pinyinKey` 稳定排序；图标改为**后台补齐**（新增 `HolographicLauncherIconLoader`，与 `HoneycombIconLoader` 同构），不再在主线程为全量几百个包同步 `getApplicationIcon`（见 `.tmp/anr/summary.md` 的前科）。为此把 `HolographicLauncherApp.icon` 由 `val` 改为 `var`。
- **UI/导航**：新增 `HolographicLauncherAppsScreen`（上半区已添加可移除、下半区可搜索全量一键添加，基于 `HiddenAppsScreen` 的既有骨架）+ `AppNavKey.HolographicLauncherApps` + 设置页「应用列表」区块新增入口 + Hub 卡片副标题改为"已添加 N 个应用"。
- **自主决策（重要，需你复核）**：**列表为空时回落到「全部应用 − 隐藏列表」，而不是显示空球。**
  - 理由：空球会被 `HolographicLauncherOverlayController.kt:27` 与 `HolographicLauncherOverlayWindow.kt:64-67` 两处 early-return 变成「点了完全没反应」，连 `Ball3DView` 的空态文案都不可达（§4 已证实）——对老用户是纯粹的倒退。
  - 代价：老用户升级后**第一眼看到的还是全部应用**，要主动进设置添加才会"变成自己的列表"。页面说明与空态文案都把这件事写清楚了。
  - 如果你要的是「默认就是空 + 首次引导」，改 `resolveApps` 的回落分支与两处 early-return 即可，我可以接着改。

### 第 5 条：快速启动器底部操作栏显隐

- `QuickLauncherDisplaySettings.showToolbar: Boolean = true`（默认开 → 老用户行为不变）+ `QUICK_LAUNCHER_SHOW_TOOLBAR` 键 + mutator/repository/UI 开关（`QuickLauncherAppearanceSettings` 顶部新增一行，四套语言文案）。
- 消费点 `QuickLauncherPanelToolbar.shouldShowToolbar()`：`… && (controller.editMode || host.settings().quickLauncherDisplay.showToolbar)`。
- **关键取舍**：**编辑态强制显示**那条栏。因为「✓」是从编辑态退出来的唯一可见出口（`setEditMode(true)` 全仓只有这里一处），设置里关掉之后若编辑态也不画，用户会卡在编辑态里。
- 因此这条按「给用户一个开关」落地，而不是**无条件**去掉 `+`/`−`：无条件去掉会同时丢掉「+ 添加快捷项」和「⇄ 切面板」，且长按已被三处占用（§5 已证实），需要一个替代入口设计才能做。要"默认就关操作栏"只需把 `showToolbar` 默认值改成 `false`。

### 改动文件清单（48 个）

- 新增 5：`HolographicLauncherAppsScreen.kt`、`HolographicLauncherIconLoader.kt`、`HolographicLauncherAppPackagesCodec.kt`、`HolographicLauncherAppPackagesCodecTest.kt`、本文件。
- 其余为上述 4 条涉及的既有文件修改（app 模块 23 个、feature:settings 模块 11 个、资源 8 个、单测 1 个）。
- **未提交、未推送**（严格遵守仓库红线：只做用户点名的改动，不自行 push）。
