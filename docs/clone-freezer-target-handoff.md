# 应用分身：现状与「冰箱目标模型」交接

> 给新会话/新协作者的最小上下文。读完这一份就能接着做，不需要翻聊天记录。
>
> 仓库：`D:\AndroidDev\Projects\cebian`（Android，Kotlin/Compose）
> 验证机：魅族 21，Flyme 12.6.0.0A（Android 16），分身 user = **999**（`android.os.usertype.profile.CLONE`），Sui 模块提供 Shizuku。
> 分支：`main`；代码基线 `e7696df6`；本文件在 `b2aee5b9` 引入，之后随实现更新。

## 零、总原则：分身没有特例

分身就是「同一个包在另一个 user 里的实例」，不是二等公民。所有能力必须与主实例完全一致：

- 在冰箱里是独立条目，不合并成一个；
- 能冻结、解冻、暂停、取消暂停；
- 能多选、全选、批量冻 / 批量解冻 / 批量暂停 / 批量取消暂停 / 按记录还原；
- 能从管理页加入、移出，能在索引与搜索里显示冻结 / 暂停角标；
- 能钉到桌面，且钉出来启动的是它自己，不是主实例；
- 启动、自动解冻、失败提示、`freezerAppIntents` 记账，全部按目标走。

**不允许**出现「分身不支持暂停」「分身不参与多选」「分身菜单里隐藏某项」这类降级。想加特例就说明数据模型不对，回去改 `(包名, userId)`。

## 实施状态（2026-10-24）

冰箱目标模型**已实现**，提交 `27bd9038`（`feat(clone): 冰箱按 (包名,userId) 建模，分身与主实例同权`）。

设备（魅族 21 / Flyme 12.6，Sui 未授权）上已手工验证：

- 旧数据（5 个裸包名成员）原样显示、解析为 user 0；
- 管理页出现「抖音 · 分身」，加入后冰箱出现两条独立条目（主 / 分身）；
- 分身状态按 user 独立读取：`pm disable-user --user 999` 后菜单变「解冻 / 解冻并移除」，主实例（暂停）不受影响；`pm enable --user 999` 后恢复「冻结」；
- 移除分身只删分身，主实例与其他成员仍在（设置回到 5 个目标）；
- 多选「全选」选中 5 项，批量栏正常；
- 快捷方式 id 为 `freezer_app_<pkg>_u999`，intent 带 `extra_freezer_launch_user_id=999`；经跳板启动后前台是 `u999 com.ss.android.ugc.aweme`（无 Shizuku 时经过 Flyme 的「选择应用」，属系统行为）。

**跨 user 挂起的平台规则（实测）**：`pm suspend --user 999` 在 **Shizuku / shell 下**被 PMS 拒绝：

```
SecurityException: Suspending package com.android.shell in user 999 does not belong to calling uid 2000
```

发起方包必须存在于目标 user 且 uid 匹配。**root 豁免这条检查**——用户已在应用内实测：root 模式点「暂停」分身成功。所以准确的结论是：

- **root（含 Shizuku-root）**：分身冻结 / 暂停都可用，代码无需改动（同一条 `setAppSuspended(..., userId)`）。但**分身暂停是单向门**：用 Root 暂停后，切到 Shizuku 非 root 既不能暂停也不能取消暂停（`unsuspend` 走同一套校验），必须切回 Root 才能恢复；应用在首次用 Root 暂停分身时会提示这一点；
- **Shizuku 非 root**：分身能冻结（`pm disable-user --user N`），**暂停 / 取消暂停都会被 PMS 拒绝**；应用分别提示「分身暂停需要 Root」「该分身由 Root 暂停，取消暂停需要切回 Root 模式」；
- 主实例的冻结 / 暂停不受影响。

另外修掉一处索引重复：主实例处于暂停时会被可启动列表排除，`loadIndexApps` 曾同时补出「抖音」（主）与「抖音」（分身目标，无后缀），`enrich` 又补一条「抖音 · 分身」，索引里出现 3 个抖音。现在 `loadIndexApps` 只补 user 0 的条目，分身统一交给 `enrich`，并在索引合并处按 `(包名, userId)` 去重。

仍需手工验证：分身取消暂停（Root 下往返一次，再切 Shizuku 确认提示）、暂停后点开是否有系统对话框、批量暂停含分身、按记录还原含分身、索引里冻结分身的角标、悬空目标。

## 一、已经做完的（不要重做）

### 1. 分身识别（P0/P1）

- `CloneAppsIndex`（`app/src/main/java/com/slideindex/app/overlay/CloneAppsIndex.kt`）
  - 公开 API 优先：`UserManager.getUserProfiles()` + `LauncherApps.getActivityList(null, user)`；
  - 特权兜底：`pm list users` / `pm list packages --user N` / `dumpsys user`（分类用）；
  - 分类：`CLONE_PROFILE` / `MANAGED_PROFILE` / `UNKNOWN`（无特权时退化为 UNKNOWN，标签仍是「· 分身」）；
  - 结果缓存 30s，失败负缓存 5s；`invalidate()` 由 `LauncherAppsCallbackBridge` 在**非当前 user** 的包事件里调用。
- 诊断日志：`adb logcat -s CloneAppsIndex CloneAppLauncher`，关键行：
  ```
  spaces={999=CLONE_PROFILE}
  user=999 kind=CLONE_PROFILE launcher=1 shell=14 total=14
  ```
- 索引合并修复：`SideOverlayController` 用 `cloneAppsExtra` 单独存分身条目，并按 `(package, cloneUserId)` 合并；装/卸应用不再把分身冲掉。
- 搜索：`AppRepository` 通过 `CloneAppProvider` 注入分身，`appsForSearch` 可搜到「抖音 · 分身」，点击走跨 user 启动。

### 2. 绑定（P2）

- 统一目标编码 `AppTarget`（`core/gesture/.../launcher/QuickLauncherItem.kt`）：
  - 主实例 = 裸包名；分身 = `包名\u0002userId`；解析无歧义，旧数据天然是 user 0。
- `GestureAction.LaunchApp` 增加 `cloneUserId`，`QuickLauncherItemCodec.encodeActionBody/decodeActionBody` 支持 `包名[\u0002id][\u001D启动形态]`，旧数据兼容。
- 手势选择器、快捷启动器添加页/文件夹页、抽屉/蜂窝/环图标查找、任务切换器标签都已按目标处理。
- 核心单测：`core/gesture/src/test/.../QuickLauncherItemCodecTest.kt` 覆盖 clone 往返与旧数据。

### 3. 无特权启动（P1 的硬骨头）

- 现象：从浮层（后台）直接 `LauncherApps.startMainActivity` 会被 Flyme 拦：
  - 先弹 `AppSwitchWarningActivity`「X手势 正在尝试打开抖音」，确认后 `result code=-93`（`START_CANCELED`）；
  - 或弹 Flyme 的 `com.android.intentresolver/.FlymeMultiOpenChooserActivity`「选择应用：抖音 / 抖音分身」。
- 解决：透明前台跳板 `CloneLaunchTrampolineActivity`（`app/src/main/java/com/slideindex/app/service/`）
  - `singleTask`、`excludeFromRecents`、`Theme.SlideIndex.Transparent`；
  - `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`（不吞触摸）；
  - 在 `onResume` 里调 `LauncherApps.startMainActivity(component, user999)`；
  - **保持存活 60s**（提前销毁会让确认后返回 -93）；取消选择（onResume after onPause）或 3s 内无界面盖上来则立刻 finish；
  - Shizuku 可用时仍优先走 shell `am start --user 999`（不弹选择器）。
- 结论：无特权下**枚举/搜索/启动都可用**，但会经过 Flyme 自己的「选择应用」（系统行为，不是我们的弹窗）。

### 4. 相关提交

```
9eee0c62 fix(clone): 跳板在取消选择/无界面时及时退出，避免卡屏
216eee14 fix(clone): 跳板改不可触摸 + singleTask，避免透明窗吞点击
f61c8020 fix(clone): 跳板保活到用户完成选择，避免确认后 START_CANCELED
c64f4823 fix(clone): 无特权启动改走前台 Trampoline，绕开 Flyme 的取消链路
ebb99a49 feat(clone): 应用分身可绑手势与快捷启动器
573a69c3 chore(clone): 补分身枚举诊断日志，单用户设备跳过 dumpsys
45e1c163 feat(clone): 分身识别公开 API 优先，修复合并不丢条目
```

## 二、唯一没做的：冰箱按 `(包名, userId)` 建模

### 2.1 目标编码与存储

- 统一目标串：主实例 = 裸包名；分身 = `AppTarget.encode(pkg, userId)`（`pkg\u0002id`）。包名不可能含 `\u0002`，无歧义。
- `freezerAppPackages: Set<String>` 语义改为目标串，**不新增字段、不做迁移**：旧值裸包名 = user 0。
- `freezerAppIntents` 的 key 同样换成目标串，否则主/分身的「上次是冻结还是暂停」会互相覆盖。
- `feature/settings` 只改 KDoc/注释，不改类型、不改 key。

### 2.2 成员解析（含已禁用 / 已挂起的条目）

- user 0 目标 → 现有 `AppRepository.resolveFreezerMembers(裸包名集合)`。
- 分身目标 → `lookupApp(pkg)?.copy(cloneUserId = id, label = 「xxx · 分身」)`；找不到时退回 `CloneAppsIndex`。
- **必须能解析到已冻结/已暂停的目标**：`LauncherApps` 默认不返回 disabled 包，clone 查询要带 `MATCH_DISABLED_COMPONENTS`（主实例同理）。否则一冻结条目就消失。
- 合并结果按 `pinyinKey` 排序。

### 2.3 状态读取（按 user）

- `FreezerPrivilegedOps.appFlags / isAppDisabled / isAppSuspended / appState` 全部增加 userId 参数。
- 公开 `PackageManager.getApplicationInfo` 没有 userId 参数，按此优先级：
  1. `LauncherApps.getApplicationInfo(pkg, flags, UserHandle)`（clone 已验证可用）；
  2. 隐藏 `IPackageManager.getApplicationInfo(String, long, int)`，走 `bindShizukuPackageManager()`（`getApplicationEnabledSetting` 已有同样先例，见 `FreezerPrivilegedOps.kt` 内 `getApplicationEnabledSetting`）；
  3. shell `pm list packages --user N [-d] -f` / `dumpsys package` 兜底。
- `waitForState` / `waitForSuspended` 的回读也必须按 user —— 它们现在是「假成功」的来源（见下）。
- 对外收口成 `stateOf(context, app: AppInfo)`；网格、菜单、`launchAndRestore` 全部走它。

### 2.4 冻结 / 解冻（按 user）

- `setAppDisabled(context, pkg, disabled)` 现在遍历 `pm list users` 的每个 user（`FreezerPrivilegedOps.kt:84`）。目标模型下必须退化为「只作用目标 user」，否则冻分身会连主实例一起冻。
- 对外暴露 `setAppDisabledForUser(context, pkg, disabled, userId)`（内部已有同名 private 函数，`FreezerPrivilegedOps.kt:101`）。
- 保持现有语义：先 `am force-stop --user N`，再 `setApplicationEnabledSetting(..., userId)`；启用档位仍按 root / 非 root 分 `DISABLED` / `DISABLED_USER`。
- `setFrozen(context, app)`：目标处于暂停态时（按同一 user 查）先取消暂停；成功后写 `freezerAppIntents[target] = FROZEN`。

### 2.5 暂停 / 取消暂停（按 user）

- 写：`setAppSuspended(context, pkg, suspended, dialogMessage, userId)`。`setPackagesSuspendedAsUser` 的第 7 / 9 个参数本来就是 userId（`FreezerPrivilegedOps.kt:243`、`:245`），`pm suspend --user N` 也已拼好（`:277`），只是调用点固定成 `resolveUserId()`（`:179`）。
- 读：同 2.3。
- 记账：PMS 按 `(包, user, 发起方包名)` 记录挂起；现有代码取消时同时清「本应用」与 `com.android.shell` 两个 key（`:196-204`），这套逻辑对 999 同样适用，但 `waitForSuspended` 必须按 999 回读。
- 冻结与暂停互斥（「先取消暂停再冻结」「先解冻再暂停」）保留，只是都按目标。
- 设备待验 4 条：Flyme 是否允许对 999 挂起、分身桌面图标是否灰化、点开是否弹系统「已暂停」对话框、`pm suspend --user 999` 的 shell 兜底是否被接受。
- 设备实测结论见「实施状态」：**Shizuku / shell 非 root** 下 `pm suspend --user 999` 会被 PMS 以 `SecurityException` 拒绝（发起方包不在目标 user）；**root 豁免**，应用内实测暂停分身成功。**这不是要恢复「分身不支持暂停」**；代码保持同一条路径，Shizuku 非 root 时提示「分身暂停需要 Root」。

### 2.6 网格 / 选择 / 批量

- 网格 key、选中集、全选、`items(key = ...)` 全部用目标串（`AppTarget.encode`）。
- `selectedPackages: Set<String>` 类型不变，但存目标串；`onToggleSelection(target)`。
- 批量冻结 / 解冻 / 暂停 / 取消暂停 / 按记录还原全部按目标、可混选主与分身（`pauseAll / unpauseAll / unfreezeAll / restoreIntents` 改成接收目标集合或 `List<AppInfo>`）。
- 单项失败只影响该项计数，沿用现有「返回成功条数」的约定。

### 2.7 管理页

- `FreezerAppsPickerScreen` 列表补分身条目（复用 `CloneAppsIndex.enrich`），勾选写目标串；已加入的分身要能取消勾选（移除目标）。
- 分身条目的标签与图标与索引/搜索一致（「抖音 · 分身」）。

### 2.8 索引 / 过滤 / 自动解冻

- `SideOverlayController`、`AppRepository.loadIndexApps` 里所有 `in freezerAppPackages` 判断改成目标匹配：主实例只认 user 0 的目标。
- 索引里的分身条目要能显示冻结 / 暂停角标（依赖 2.2 的 disabled 解析）。
- 点击冻结项 → 自动解冻后启动；分身走 `am start --user N` 或跳板，无特权时过 Flyme 选择器（正常）。

### 2.9 移除 / 恢复

- `FreezerListOperations.removeFromList / restoreAndRemoveFromList / removeManyFromList / restoreAndRemoveManyFromList` 的参数从 `packageName` 改为目标（`AppTarget` 或 `AppInfo`）。
- 移除已冻结的分身：只对 999 `pm enable --user 999`，主实例不受影响；反之亦然。

### 2.10 加到桌面（快捷方式）

- `FreezerAppShortcutHelper`：shortcut id 必须含 userId（`freezer_app_$target`），label / 图标取分身自己的。
- `FreezerAppLaunchTrampolineActivity` 增加 `EXTRA_USER_ID`，落点解析用目标（`resolveFreezerMembers(setOf(target))`），启动走对应 user。
- 否则：分身钉到桌面会开主实例，且主/分身快捷方式 id 相同会互相覆盖。

### 2.11 容错

- 悬空目标（分身被删、应用卸载）：`AppTarget.parse` 后解析不到 → 跳过，不崩、不阻塞其他条目；管理页可移除。
- `FreezerBootstrap.importFrozenApps` 从雹（Hail）导入的包写当前 user 的裸包名（= user 0），不要写成 clone 目标。
- 用户列表缓存 30s（`USER_IDS_CACHE_MS`），新建 / 删除分身后最迟半分钟生效，验证时注意。

### 2.12 有意的范围限制

- 只枚举 user 0 与 clone 用户；工作资料（managed profile）暂不进管理页。模型支持任意 userId，未来放开枚举即可，不需要改存储。
- 主实例的「冻结」不再顺带冻结分身（这正是本次要修的语义）。

### 现状里已知会出错的 4 处（改造时必须一起修）

1. **回读只看当前用户**：`waitForState` / `waitForSuspended` 用 `context.packageManager` 查当前 user（`FreezerPrivilegedOps.kt:452`、`:315`），所以「冻结/暂停分身成功」是假的 —— 现在它对所有 user 都执行写操作，却只验证了 user 0。
2. **冻结后分身消失**：`CloneAppsIndex` 公开 API 路径用 `LauncherApps.getActivityList(null, user)`，disabled 包默认不返回；冻结的分身会从搜索/索引里消失。
3. **快捷方式**：id 用裸包名 + 跳板只带 `EXTRA_PACKAGE`（`FreezerAppShortcutHelper.kt:38`、`FreezerAppLaunchTrampolineActivity.kt:44`），分身钉桌面会开主实例。
4. **全 user 冻结**：`setAppDisabled` 遍历所有 user（`FreezerPrivilegedOps.kt:84`），不退化成单 user 就会「冻一个冻一双」。

### 要动的文件清单

- `app/.../freezer/FreezerPrivilegedOps.kt`（按 user 的 disable/enable、suspend、回读）
- `app/.../freezer/FreezerOperations.kt`（`stateOf(app)` / `setFrozen(app)` / `setPaused(app)` / `launchAndRestore` / 批量 / `restoreIntents`）
- `app/.../freezer/FreezerPanelContent.kt`（成员合并、选中/批量）
- `app/.../freezer/FreezerGridUi.kt`（key / 选中 / 菜单 / 单击）
- `app/.../freezer/FreezerListOperations.kt`（按目标移除/恢复）
- `app/.../freezer/FreezerAppShortcutHelper.kt` + `FreezerAppLaunchTrampolineActivity.kt`（快捷方式带 userId）
- `app/.../freezer/FreezerBootstrap.kt`（导入目标）
- `app/.../ui/FreezerAppsPickerScreen.kt`（分身条目与勾选）
- `app/.../overlay/SideOverlayController.kt`（索引过滤）
- `app/.../overlay/CloneAppsIndex.kt`（disabled 条目可见性）
- `feature/apps/.../data/AppRepository.kt`（`loadIndexApps` 的 freezer 参数、目标版成员解析）
- `feature/settings/...`（只改 KDoc 注释）

### 验证清单（必须逐条过）

1. 旧冰箱成员（裸包名）原样显示，可冻结 / 解冻 / 暂停 / 还原。
2. 管理页出现「抖音 · 分身」，加入后冰箱两条：主 / 分身。
3. 只冻结分身：主实例可启动、桌面图标不变；只冻结主实例：分身可启动。
4. 只暂停分身：主实例正常，分身图标灰化、点开弹系统对话框；只暂停主实例：分身正常。**需要 root**（Shizuku 非 root 时 PMS 拒绝跨 user 挂起；root 已实测可用）。
5. 多选「主 + 分身」→ 批量冻结 / 批量解冻 / 批量暂停 / 批量取消暂停，四个动作都独立生效。
6. 「按记录还原」对主 / 分身各自生效（`freezerAppIntents` key 不互相覆盖）。
7. 移除分身条目只删分身：已冻结时只 `pm enable --user 999`，主实例仍在列表。
8. 冻结 / 暂停的分身在索引里仍有条目 + 角标，点击能自动解冻再启动（无特权时走 Flyme 选择器，属正常）。
9. 加到桌面：主、分身各钉一个，各自启动正确实例，两个快捷方式不互相覆盖。
10. 删掉分身（删除 user 999）或卸载应用后冰箱不崩，残留条目可移除。
11. 回读真实性：冻结后 `adb shell pm list packages --user 999 -d | findstr aweme` 能查到；暂停后 `adb shell dumpsys package com.ss.android.ugc.aweme` 的 `User 999` 段显示 `suspended=true`。不要只看 Toast。

## 三、开发/验证约定

- 编译：`.\gradlew.bat :app:compileFullDebugKotlin --offline`
- 装机：`.\gradlew.bat :app:assembleFullDebug --offline`，然后
  `adb install -r app\build\outputs\apk\full\debug\app-full-debug.apk`
- 日志：`adb logcat -s CloneAppsIndex CloneAppLauncher CloneLaunchTrampoline`
- 仓库红线（`AGENTS.md`）：只逐个 `git add` 点名文件；**不 push**；改完跑 `python scripts/normalize-eol.py --check <files>`；不要 `git add -A`。
- 设备上 Shizuku 当前可能处于**未授权**状态（用户用 Sui 模块）；测试特权路径前先在 Sui 管理器授权。无特权时：
  - 分身枚举 / 搜索正常；
  - 启动会经过 Flyme 的「选择应用」，属系统行为；
  - 冻结 / 暂停 / 批量等特权功能不可用。
- 主实例抖音当前可能处于 `suspended=true`（暂停），会影响「普通抖音打不开」的判断，测前先在冰箱取消暂停。
- 改完自查（防特例回归）：
  - `grep -n "packageName" app/.../freezer/` 里是否还有未目标化的调用点；
  - `grep -n "in freezerAppPackages"` 是否还有裸包名比较；
  - 菜单/批量里是否还有 `cloneUserId` 分支在**隐藏**某个动作。
  - 任何一处 「if (clone) 就不做 X」 都是 bug，不是设计。

## 四、新会话怎么开工

直接说：

> 读 `docs/clone-freezer-target-handoff.md`，按「二、冰箱按 (包名,userId) 建模」开始改；分身不允许有特例，暂停也要按 user 支持；一步一编译，最后装机并跑验证清单。

建议拆成 5 个提交：

1. `FreezerPrivilegedOps`：按 user 的 disable/enable + suspend + per-user 回读（含 `waitFor*`）。
2. `FreezerOperations`：`AppInfo` 重载（`stateOf/setFrozen/setPaused/launchAndRestore`）、目标解析、批量与还原。
3. 成员解析（主 + 分身，含 disabled）+ 面板/网格 key/选中/批量。
4. 管理页勾选 + 移除/恢复 + 加到桌面（跳板 `EXTRA_USER_ID`）。
5. 索引 disabled 可见性 + 悬空容错 + 单测与验证修复。
