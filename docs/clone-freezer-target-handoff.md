# 应用分身：现状与「冰箱目标模型」交接

> 给新会话/新协作者的最小上下文。读完这一份就能接着做，不需要翻聊天记录。
>
> 仓库：`D:\AndroidDev\Projects\cebian`（Android，Kotlin/Compose）
> 验证机：魅族 21，Flyme 12.6.0.0A（Android 16），分身 user = **999**（`android.os.usertype.profile.CLONE`），Sui 模块提供 Shizuku。
> 分支：`main`；代码最新提交 `9eee0c62`，本文件由 `b2aee5b9` 引入（其后没有新的代码提交）。

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

### 目标语义

冰箱里的主实例和分身是**两个独立目标**：各自出现在列表、各自冻结/解冻、都可以多选和批量；旧数据（只有包名）等价于 user 0，**不需要改写磁盘**。

### 设计（推荐直接照此实现）

1. **存储**：`freezerAppPackages: Set<String>` 语义改为「目标字符串」：
   - 主实例仍写裸包名（兼容旧数据）；
   - 分身写 `AppTarget.encode(pkg, userId)`（`pkg\u0002id`）。
   - 不新增字段、不做迁移；读取时统一 `AppTarget.parse`。
2. **成员解析**：
   - user 0 的目标 → 现有 `AppRepository.resolveFreezerMembers(packages)`；
   - clone 目标 → `appRepository.lookupApp(pkg)?.copy(cloneUserId = id, label = CloneAppsIndex.labelForUser(...))`；
   - 结果合并、按 `pinyinKey` 排序。
3. **状态/操作**：
   - `FreezerOperations.stateOf(context, app: AppInfo)`：clone 用 `LauncherApps.getApplicationInfo(pkg, MATCH_DISABLED_COMPONENTS, user)` 得到 enabled/suspended；主实例走原逻辑。
   - `FreezerOperations.setFrozen(context, app: AppInfo, frozen)`：clone 用 `pm disable-user --user N` / `pm enable --user N`（经 `TaskManagerUtil.runShellCommandOutput`）；主实例走原逻辑。
   - 分身暂不支持「暂停」：菜单隐藏暂停、批量暂停跳过 clone；`setPaused(app)` 对 clone 返回 false 并提示。
4. **面板/网格**：
   - `FreezerPanelContent.memberApps` 用上面的合并结果；
   - `FreezerGridUi` 的 key、选中态、全选、批量都换成 `AppTarget.encode(app.packageName, app.cloneUserId)`；
   - 冻结/解冻调用 `setFrozen(context, app, ...)`，不要再用 `app.packageName`。
5. **管理页**：`FreezerAppsPickerScreen` 列表补分身条目（可复用 `CloneAppsIndex.enrich`），勾选写目标字符串。
6. **索引/过滤**：`SideOverlayController`、`AppRepository.loadIndexApps` 里所有 `in freezerAppPackages` 的判断改成目标匹配；主实例只认 user 0。
7. **批量还原**：`freezerAppIntents` 的 key 同样用目标字符串；`restoreIntents` 逐个 `AppTarget.parse`，clone 只做解冻。
8. **列表移除/恢复**：`FreezerListOperations.removeFromList/restoreAndRemoveFromList` 改为接收目标字符串；clone 移除只删目标、必要时 `pm enable --user N`。

### 要动的文件清单

- `app/.../freezer/FreezerPrivilegedOps.kt`（新增按 user 的 disable/enable）
- `app/.../freezer/FreezerOperations.kt`（`stateOf(app)` / `setFrozen(app)`；批量解析目标）
- `app/.../freezer/FreezerPanelContent.kt`（成员合并、选中/批量）
- `app/.../freezer/FreezerGridUi.kt`（key/选中/菜单）
- `app/.../freezer/FreezerListOperations.kt`（按目标移除/恢复）
- `app/.../ui/FreezerAppsPickerScreen.kt`（分身条目与勾选）
- `app/.../overlay/SideOverlayController.kt`（索引过滤）
- `feature/apps/.../data/AppRepository.kt`（`loadIndexApps` 的 freezer 参数）
- `feature/settings/...`：只加注释说明 `freezerAppPackages` 现在是目标字符串；不改类型。

### 验证清单（必须逐条过）

1. 旧冰箱成员（裸包名）原样显示，可冻结/解冻；
2. 管理页能看到「抖音 · 分身」，加入后冰箱出现两条：主 / 分身；
3. 只冻结分身：主实例仍可启动、桌面图标不变；
4. 只冻结主实例：分身仍可启动；
5. 多选「主 + 分身」→ 批量冻结 → 两者都冻；批量解冻 → 两者都恢复；
6. 「按记录还原」对主/分身各自生效（`freezerAppIntents` key 不互相覆盖）；
7. 移除分身条目只删分身，主实例仍在；
8. 索引里冻结分身显示冻结角标，点击能自动解冻并启动（无特权时走 Flyme 选择器，属正常）。

## 三、开发/验证约定

- 编译：`.\gradlew.bat :app:compileFullDebugKotlin --offline`
- 装机：`.\gradlew.bat :app:assembleFullDebug --offline`，然后
  `adb install -r app\build\outputs\apk\full\debug\app-full-debug.apk`
- 日志：`adb logcat -s CloneAppsIndex CloneAppLauncher CloneLaunchTrampoline`
- 仓库红线（`AGENTS.md`）：只逐个 `git add` 点名文件；**不 push**；改完跑 `python scripts/normalize-eol.py --check <files>`；不要 `git add -A`。
- 设备上 Shizuku 当前可能处于**未授权**状态（用户用 Sui 模块）；测试特权路径前先在 Sui 管理器授权。无特权时：
  - 分身枚举/搜索正常；
  - 启动会经过 Flyme 的「选择应用」，属系统行为；
  - 冻结/批量等特权功能不可用。
- 主实例抖音当前可能处于 `suspended=true`（暂停），会影响“普通抖音打不开”的判断，测前先在冰箱取消暂停。

## 四、新会话怎么开工

直接说：

> 读 `docs/clone-freezer-target-handoff.md`，按「二、冰箱按 (包名,userId) 建模」开始改，一步一编译，最后装机并跑验证清单。

建议拆成 4 个提交：

1. `FreezerPrivilegedOps` 按 user 的 disable/enable + `FreezerOperations` 的 `AppInfo` 重载；
2. 成员解析（主 + 分身）与面板/网格 key/选中/批量；
3. 管理页勾选目标 + 列表移除/恢复；
4. 索引过滤、批量还原、单测与验证修复。
