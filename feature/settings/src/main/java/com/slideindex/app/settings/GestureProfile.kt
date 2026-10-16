package com.slideindex.app.settings

import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.gesture.GestureActionType
import com.slideindex.app.gesture.GestureAngle
import com.slideindex.app.gesture.GestureAngles
import com.slideindex.app.gesture.GestureRule
import com.slideindex.app.gesture.GestureTriggerMode
import com.slideindex.app.gesture.GestureTriggerType
import com.slideindex.app.gesture.SideGestureDefaults
import com.slideindex.app.gesture.TriggerHandle
import com.slideindex.app.gesture.TriggerHandleCodec
import com.slideindex.app.gesture.normalized
import com.slideindex.app.launcher.QuickLauncherItemCodec
import com.slideindex.app.overlay.PanelSide

/**
 * 一个「按应用分档」档位里那份**独立的触钮集合快照**（GitHub issue #26）。
 *
 * ## 为什么是"整片快照"而不是"逐槽位覆盖"
 *
 * 需求是「把当前触钮集合的整套能力带到分应用里，在配置的 App 里重新挂窗配置触钮手势」：
 * 挂哪些触钮、挂在哪、多大、什么外观、各自绑什么手势 —— 都归**档**说了算。所以档里存的就是
 * [EdgeTriggerSettings] 那一片（去掉几个系统级字段，见下）加上手势规则（**竖屏 + 横屏两套**：
 * 有的 App 就是横屏的，比如游戏）。
 *
 * ## 语义：创建时整份复制全局，之后**不跟随**
 *
 * 建档时把当时的全局整套复制进来（[from]），此后全局怎么改都与它无关；想跟随就再点一次
 * 「恢复为全局」。这与「摇一摇」的逐槽位覆盖是两种语义，**不要混**。
 *
 * ## 刻意**不**进档的字段
 *
 * `interceptSystemBackGesture` / `limitMaxInterceptLength` / `systemGestureTakeover*`：
 * 它们是 LSPosed 输入层接管与系统返回手势拦截，**本质上是系统级、按全局配置生效**
 * （`ModuleHookConfigSync` 只读全局快照），放进档里只会造成"改了没反应"的假象。
 */
data class GestureProfileSnapshot(
    val leftEdgeEnabled: Boolean = true,
    val rightEdgeEnabled: Boolean = true,
    val leftEdgeTriggerWidthDp: Float = 20f,
    val rightEdgeTriggerWidthDp: Float = 20f,
    val bottomEdgeTriggerWidthDp: Float = 20f,
    val topEdgeTriggerWidthDp: Float = 20f,
    val leftTriggerTopFraction: Float = 0.30f,
    val rightTriggerTopFraction: Float = 0.30f,
    val leftTriggerHeightFraction: Float = 0.38f,
    val rightTriggerHeightFraction: Float = 0.38f,
    val leftTriggerHandles: List<TriggerHandle> = listOf(TriggerHandle.default(0.30f, 0.38f)),
    val rightTriggerHandles: List<TriggerHandle> = listOf(TriggerHandle.default(0.30f, 0.38f)),
    val bottomTriggerHandles: List<TriggerHandle> = listOf(TriggerHandle.bottomDefault()),
    val topTriggerHandles: List<TriggerHandle> = listOf(TriggerHandle.topDefault()),
    val leftTriggerHandlesLandscape: List<TriggerHandle> = emptyList(),
    val rightTriggerHandlesLandscape: List<TriggerHandle> = emptyList(),
    val bottomTriggerHandlesLandscape: List<TriggerHandle> = emptyList(),
    val topTriggerHandlesLandscape: List<TriggerHandle> = emptyList(),
    val landscapeTriggersInitialized: Boolean = false,
    /** 竖屏手势规则（横屏那套在 [gestureRulesLandscape]）。 */
    val gestureRules: List<GestureRule> = SideGestureDefaults.defaultRules(),
    val gestureRulesLandscape: List<GestureRule> = SideGestureDefaults.defaultRules(),
    val leftDefaultTriggerMode: GestureTriggerMode = GestureTriggerMode.ON_RELEASE,
    val rightDefaultTriggerMode: GestureTriggerMode = GestureTriggerMode.ON_RELEASE,
    val bottomDefaultTriggerMode: GestureTriggerMode = GestureTriggerMode.ON_RELEASE,
    val topDefaultTriggerMode: GestureTriggerMode = GestureTriggerMode.ON_RELEASE,
    val leftDefaultTriggerModeLandscape: GestureTriggerMode = GestureTriggerMode.ON_RELEASE,
    val rightDefaultTriggerModeLandscape: GestureTriggerMode = GestureTriggerMode.ON_RELEASE,
    val bottomDefaultTriggerModeLandscape: GestureTriggerMode = GestureTriggerMode.ON_RELEASE,
    val topDefaultTriggerModeLandscape: GestureTriggerMode = GestureTriggerMode.ON_RELEASE,
    val shortSwipeDistanceDp: Float = 60f,
    val longSwipeDistanceDp: Float = 120f,
    val gestureHintEnabled: Boolean = true,
    val gestureHintStyleId: Int = GestureHintStyle.BUBBLE.id,
    val gestureHintFingerOffsetDp: Float = 0f,
    val androidBackColorSourceId: Int = AndroidBackColorSource.APP_THEME.id,
    val swipeHoverDurationMs: Int = SwipeHoverDurationLimits.DEFAULT_MS,
    val triggerDoubleTapIntervalMs: Int = 300,
    val animationStyles: AnimationStyles = AnimationStyles(),
    val gestureAngles: GestureAngles = GestureAngles(),
    val keyboardTriggerBehaviorPortrait: KeyboardTriggerBehavior = KeyboardTriggerBehavior.OVERLAY,
    val keyboardTriggerBehaviorLandscape: KeyboardTriggerBehavior = KeyboardTriggerBehavior.OVERLAY,
    val keyboardTriggerNarrowPercentPortrait: Int = KeyboardTriggerNarrowPercents.DEFAULT,
    val keyboardTriggerNarrowPercentLandscape: Int = KeyboardTriggerNarrowPercents.DEFAULT,
) {
    fun handlesFor(side: PanelSide, landscape: Boolean): List<TriggerHandle> = when (side) {
        PanelSide.LEFT -> if (landscape) leftTriggerHandlesLandscape else leftTriggerHandles
        PanelSide.RIGHT -> if (landscape) rightTriggerHandlesLandscape else rightTriggerHandles
        PanelSide.BOTTOM -> if (landscape) bottomTriggerHandlesLandscape else bottomTriggerHandles
        PanelSide.TOP -> if (landscape) topTriggerHandlesLandscape else topTriggerHandles
    }

    fun defaultTriggerModeFor(side: PanelSide, landscape: Boolean): GestureTriggerMode = when (side) {
        PanelSide.LEFT -> if (landscape) leftDefaultTriggerModeLandscape else leftDefaultTriggerMode
        PanelSide.RIGHT -> if (landscape) rightDefaultTriggerModeLandscape else rightDefaultTriggerMode
        PanelSide.BOTTOM -> if (landscape) bottomDefaultTriggerModeLandscape else bottomDefaultTriggerMode
        PanelSide.TOP -> if (landscape) topDefaultTriggerModeLandscape else topDefaultTriggerMode
    }

    companion object {
        /** 建档时用：把**当前全局**这一整片抄一份。 */
        fun from(settings: AppSettings): GestureProfileSnapshot = GestureProfileSnapshot(
            leftEdgeEnabled = settings.leftEdgeEnabled,
            rightEdgeEnabled = settings.rightEdgeEnabled,
            leftEdgeTriggerWidthDp = settings.leftEdgeTriggerWidthDp,
            rightEdgeTriggerWidthDp = settings.rightEdgeTriggerWidthDp,
            bottomEdgeTriggerWidthDp = settings.bottomEdgeTriggerWidthDp,
            topEdgeTriggerWidthDp = settings.topEdgeTriggerWidthDp,
            leftTriggerTopFraction = settings.leftTriggerTopFraction,
            rightTriggerTopFraction = settings.rightTriggerTopFraction,
            leftTriggerHeightFraction = settings.leftTriggerHeightFraction,
            rightTriggerHeightFraction = settings.rightTriggerHeightFraction,
            leftTriggerHandles = settings.leftTriggerHandles,
            rightTriggerHandles = settings.rightTriggerHandles,
            bottomTriggerHandles = settings.bottomTriggerHandles,
            topTriggerHandles = settings.topTriggerHandles,
            leftTriggerHandlesLandscape = settings.leftTriggerHandlesLandscape,
            rightTriggerHandlesLandscape = settings.rightTriggerHandlesLandscape,
            bottomTriggerHandlesLandscape = settings.bottomTriggerHandlesLandscape,
            topTriggerHandlesLandscape = settings.topTriggerHandlesLandscape,
            landscapeTriggersInitialized = settings.landscapeTriggersInitialized,
            gestureRules = settings.gestureRules,
            gestureRulesLandscape = settings.gestureRulesLandscape,
            leftDefaultTriggerMode = settings.leftDefaultTriggerMode,
            rightDefaultTriggerMode = settings.rightDefaultTriggerMode,
            bottomDefaultTriggerMode = settings.bottomDefaultTriggerMode,
            topDefaultTriggerMode = settings.topDefaultTriggerMode,
            leftDefaultTriggerModeLandscape = settings.leftDefaultTriggerModeLandscape,
            rightDefaultTriggerModeLandscape = settings.rightDefaultTriggerModeLandscape,
            bottomDefaultTriggerModeLandscape = settings.bottomDefaultTriggerModeLandscape,
            topDefaultTriggerModeLandscape = settings.topDefaultTriggerModeLandscape,
            shortSwipeDistanceDp = settings.shortSwipeDistanceDp,
            longSwipeDistanceDp = settings.longSwipeDistanceDp,
            gestureHintEnabled = settings.gestureHintEnabled,
            gestureHintStyleId = settings.gestureHintStyleId,
            gestureHintFingerOffsetDp = settings.gestureHintFingerOffsetDp,
            androidBackColorSourceId = settings.androidBackColorSourceId,
            swipeHoverDurationMs = settings.swipeHoverDurationMs,
            triggerDoubleTapIntervalMs = settings.triggerDoubleTapIntervalMs,
            animationStyles = settings.animationStyles,
            gestureAngles = settings.gestureAngles,
            keyboardTriggerBehaviorPortrait = settings.keyboardTriggerBehaviorPortrait,
            keyboardTriggerBehaviorLandscape = settings.keyboardTriggerBehaviorLandscape,
            keyboardTriggerNarrowPercentPortrait = settings.keyboardTriggerNarrowPercentPortrait,
            keyboardTriggerNarrowPercentLandscape = settings.keyboardTriggerNarrowPercentLandscape,
        )
    }
}

/**
 * 一个档位：名字 + 绑定的 App + 整片触钮集合快照。
 *
 * 一个 App **最多**属于一个档（见 [GestureProfileCatalog.profileIdOfPackage]）：两个档同时命中
 * 谁赢说不清，索性在建档/绑定时就挡掉。
 */
data class GestureProfile(
    val id: String,
    val name: String,
    val packageNames: Set<String> = emptySet(),
    val snapshot: GestureProfileSnapshot = GestureProfileSnapshot(),
) {
    fun renamed(newName: String): GestureProfile = copy(name = newName)

    fun withPackage(packageName: String): GestureProfile =
        copy(packageNames = packageNames + packageName)

    fun withoutPackage(packageName: String): GestureProfile =
        copy(packageNames = packageNames - packageName)

    fun withSnapshot(newSnapshot: GestureProfileSnapshot): GestureProfile = copy(snapshot = newSnapshot)

    companion object {
        fun newId(): String = "gp-" + java.util.UUID.randomUUID().toString().substring(0, 8)
    }
}

/** 档列表的查询工具（纯函数，运行时与 UI 共用一份，避免两边各写一套命中规则）。 */
object GestureProfileCatalog {
    /** 前台包名命中的档 id；没命中返回 null。[enabled] = 总开关（关掉时一律 null，档保留）。 */
    fun profileIdOfPackage(
        profiles: List<GestureProfile>,
        enabled: Boolean,
        foregroundPackage: String?,
    ): String? {
        if (!enabled) return null
        val pkg = foregroundPackage?.takeIf { it.isNotBlank() } ?: return null
        return profiles.firstOrNull { pkg in it.packageNames }?.id
    }

    fun profileOfPackage(
        profiles: List<GestureProfile>,
        enabled: Boolean,
        foregroundPackage: String?,
    ): GestureProfile? = profileIdOfPackage(profiles, enabled, foregroundPackage)
        ?.let { id -> profiles.firstOrNull { it.id == id } }
}

/** 把快照里的这一片写回一个 `AppSettings` 副本（其余字段原样保留）。 */
fun AppSettings.withGestureProfileSnapshot(snapshot: GestureProfileSnapshot): AppSettings = copy(
    edgeTrigger = edgeTrigger.copy(
        leftEdgeEnabled = snapshot.leftEdgeEnabled,
        rightEdgeEnabled = snapshot.rightEdgeEnabled,
        leftEdgeTriggerWidthDp = snapshot.leftEdgeTriggerWidthDp,
        rightEdgeTriggerWidthDp = snapshot.rightEdgeTriggerWidthDp,
        bottomEdgeTriggerWidthDp = snapshot.bottomEdgeTriggerWidthDp,
        topEdgeTriggerWidthDp = snapshot.topEdgeTriggerWidthDp,
        leftTriggerTopFraction = snapshot.leftTriggerTopFraction,
        rightTriggerTopFraction = snapshot.rightTriggerTopFraction,
        leftTriggerHeightFraction = snapshot.leftTriggerHeightFraction,
        rightTriggerHeightFraction = snapshot.rightTriggerHeightFraction,
        leftTriggerHandles = snapshot.leftTriggerHandles,
        rightTriggerHandles = snapshot.rightTriggerHandles,
        bottomTriggerHandles = snapshot.bottomTriggerHandles,
        topTriggerHandles = snapshot.topTriggerHandles,
        leftTriggerHandlesLandscape = snapshot.leftTriggerHandlesLandscape,
        rightTriggerHandlesLandscape = snapshot.rightTriggerHandlesLandscape,
        bottomTriggerHandlesLandscape = snapshot.bottomTriggerHandlesLandscape,
        topTriggerHandlesLandscape = snapshot.topTriggerHandlesLandscape,
        landscapeTriggersInitialized = snapshot.landscapeTriggersInitialized,
        gestureRulesLandscape = snapshot.gestureRulesLandscape,
        leftDefaultTriggerMode = snapshot.leftDefaultTriggerMode,
        rightDefaultTriggerMode = snapshot.rightDefaultTriggerMode,
        bottomDefaultTriggerMode = snapshot.bottomDefaultTriggerMode,
        topDefaultTriggerMode = snapshot.topDefaultTriggerMode,
        leftDefaultTriggerModeLandscape = snapshot.leftDefaultTriggerModeLandscape,
        rightDefaultTriggerModeLandscape = snapshot.rightDefaultTriggerModeLandscape,
        bottomDefaultTriggerModeLandscape = snapshot.bottomDefaultTriggerModeLandscape,
        topDefaultTriggerModeLandscape = snapshot.topDefaultTriggerModeLandscape,
        shortSwipeDistanceDp = snapshot.shortSwipeDistanceDp,
        longSwipeDistanceDp = snapshot.longSwipeDistanceDp,
        gestureHintEnabled = snapshot.gestureHintEnabled,
        gestureHintStyleId = snapshot.gestureHintStyleId,
        gestureHintFingerOffsetDp = snapshot.gestureHintFingerOffsetDp,
        androidBackColorSourceId = snapshot.androidBackColorSourceId,
        swipeHoverDurationMs = snapshot.swipeHoverDurationMs,
        triggerDoubleTapIntervalMs = snapshot.triggerDoubleTapIntervalMs,
        animationStyles = snapshot.animationStyles,
        gestureAngles = snapshot.gestureAngles,
        keyboardTriggerBehaviorPortrait = snapshot.keyboardTriggerBehaviorPortrait,
        keyboardTriggerBehaviorLandscape = snapshot.keyboardTriggerBehaviorLandscape,
        keyboardTriggerNarrowPercentPortrait = snapshot.keyboardTriggerNarrowPercentPortrait,
        keyboardTriggerNarrowPercentLandscape = snapshot.keyboardTriggerNarrowPercentLandscape,
    ),
    launcher = launcher.copy(gestureRules = snapshot.gestureRules),
)

/**
 * 运行时/UI 的**唯一**入口：按当前前台 App 求出"这一帧真正生效的设置"。
 *
 * - 命中某个档 → 返回"全局 + 档里那一整片覆盖"的副本；
 * - 没命中 / 总开关关 → 原样返回（不复制）。
 *
 * ⚠️ 返回的仍是 `AppSettings`，所以**下游一切读法都不用改**：动作解析（`actionFor`）、触钮几何、
 * 外观、触发模式、横屏那套、键盘行为全都自动跟着档走 —— 这也是这次能不动手势引擎的原因。
 */
fun AppSettings.withActiveGestureProfile(foregroundPackage: String?): AppSettings {
    val profile = GestureProfileCatalog.profileOfPackage(
        profiles = gestureProfiles,
        enabled = independentAppGestureEnabled,
        foregroundPackage = foregroundPackage,
    ) ?: return this
    return withGestureProfileSnapshot(profile.snapshot)
}

/**
 * 档位集合的存储编解码。
 *
 * ## 格式：**一个字段一条记录**，值 = "这条记录里最后一个头分隔符之后的全部内容"
 *
 * `gp1\u001F<档id>\u001F<kind>\u001F[<subKey>\u001F]<value>`
 *
 * - `name` / `pkg`：档名、绑定的包名（各一条；档名在建档时洗掉控制字符）；
 * - `num`：所有数值/布尔/枚举字段打成一个 `k=v;k=v` 串（键是自己定的、值是数字或枚举名，
 *   不可能含分隔符）；
 * - `anim`：整个 [AnimationStyles] 走 [AnimationStyleCodec]（它的 payload 是 JSON，含任意字符）；
 * - `h` / `hl`：竖屏 / 横屏触钮组，一条一个，值 = [TriggerHandleCodec.encode]；
 * - `r` / `rl`：竖屏 / 横屏手势规则，一条一个，值 = 本文件自己那套"安全分段"（见下）。
 *
 * ## 为什么规则**不直接用** `GestureRuleCodec`
 *
 * `GestureRuleCodec.decode` 是 `split('\u001F')` + 段数必须等于 9/8/7 —— 而**动作正文里本来就
 * 可能带 `\u001F`**（多 intent 快捷方式的 `INTENT_LIST_SEP`），那种规则会被整条判成坏数据丢掉。
 * 这里改成"只切前 8 个分隔符、正文取剩下全部"，信息量一样但不怕正文带分隔符。
 * （教训：凡是要把**不透明正文**拼进一条记录，都不要用某个字符做"列表"分隔符。）
 */
object GestureProfileCodec {
    private const val SEP = "\u001F"
    private const val VERSION = "gp1"
    private const val RULE_FIELDS = 8

    fun encodeAll(profiles: List<GestureProfile>): Set<String> = buildSet {
        for (profile in profiles) {
            val id = profile.id.takeIf { it.isNotBlank() && !it.contains(SEP) } ?: continue
            add(record(id, "name", sanitizeName(profile.name)))
            for (pkg in profile.packageNames) {
                if (pkg.isBlank() || pkg.contains(SEP)) continue
                add(record(id, "pkg", pkg))
            }
            add(record(id, "num", encodeScalars(profile.snapshot)))
            add(record(id, "anim", AnimationStyleCodec.encode(profile.snapshot.animationStyles)))
            for (side in PanelSide.entries) {
                for (handle in profile.snapshot.handlesFor(side, landscape = false)) {
                    add(record(id, "h", side.name, TriggerHandleCodec.encode(handle)))
                }
                for (handle in profile.snapshot.handlesFor(side, landscape = true)) {
                    add(record(id, "hl", side.name, TriggerHandleCodec.encode(handle)))
                }
            }
            for (rule in profile.snapshot.gestureRules) add(record(id, "r", encodeRule(rule)))
            for (rule in profile.snapshot.gestureRulesLandscape) add(record(id, "rl", encodeRule(rule)))
        }
    }

    fun decodeAll(raw: Set<String>): List<GestureProfile> {
        val builders = LinkedHashMap<String, Builder>()
        for (entry in raw) {
            val header = splitHeader(entry) ?: continue
            val builder = builders.getOrPut(header.id) { Builder(header.id) }
            when (header.kind) {
                "name" -> builder.name = header.value
                "pkg" -> if (header.value.isNotBlank()) builder.packages += header.value
                "num" -> builder.scalars = parseScalars(header.value)
                "anim" -> builder.animationStyles = AnimationStyleCodec.decode(header.value)
                "h", "hl" -> {
                    val side = PanelSide.entries.firstOrNull { it.name == header.subKey } ?: continue
                    val handle = TriggerHandleCodec.decode(header.value) ?: continue
                    if (header.kind == "h") {
                        builder.handles.getOrPut(side) { mutableListOf() } += handle
                    } else {
                        builder.handlesLandscape.getOrPut(side) { mutableListOf() } += handle
                    }
                }
                "r" -> decodeRule(header.value)?.let { builder.rules += it }
                "rl" -> decodeRule(header.value)?.let { builder.rulesLandscape += it }
            }
        }
        return builders.values.map { it.build() }
    }

    private fun record(id: String, kind: String, value: String): String =
        "$VERSION$SEP$id$SEP$kind$SEP$value"

    private fun record(id: String, kind: String, subKey: String, value: String): String =
        "$VERSION$SEP$id$SEP$kind$SEP$subKey$SEP$value"

    private data class Header(val id: String, val kind: String, val subKey: String, val value: String)

    private fun splitHeader(entry: String): Header? {
        val versionEnd = entry.indexOf(SEP)
        if (versionEnd <= 0 || entry.substring(0, versionEnd) != VERSION) return null
        val idEnd = entry.indexOf(SEP, versionEnd + 1)
        if (idEnd <= versionEnd + 1) return null
        val id = entry.substring(versionEnd + 1, idEnd)
        val kindEnd = entry.indexOf(SEP, idEnd + 1)
        if (kindEnd <= idEnd + 1) return null
        val kind = entry.substring(idEnd + 1, kindEnd)
        val afterKind = entry.substring(kindEnd + 1)
        if (kind != "h" && kind != "hl") return Header(id, kind, "", afterKind)
        val subEnd = afterKind.indexOf(SEP)
        if (subEnd <= 0) return null
        return Header(id, kind, afterKind.substring(0, subEnd), afterKind.substring(subEnd + 1))
    }

    /** 档名是用户输入：只允许普通可见字符，控制字符一律换成空格（免得写坏记录结构）。 */
    internal fun sanitizeName(name: String): String = name
        .map { ch -> if (ch.code < 0x20 || ch.code == 0x7F) ' ' else ch }
        .joinToString("")
        .trim()
        .take(NAME_MAX_CHARS)

    private const val NAME_MAX_CHARS = 60

    // ── 规则：安全分段（正文整段保留） ─────────────────────────────────────
    private fun encodeRule(rule: GestureRule): String = listOf(
        rule.id,
        rule.side.ordinal.toString(),
        rule.handleId,
        rule.trigger.id.toString(),
        rule.action.type.id.toString(),
        rule.priority.toString(),
        if (rule.enabled) "1" else "0",
        rule.triggerMode.id.toString(),
    ).joinToString(SEP) + SEP + QuickLauncherItemCodec.encodeActionBody(rule.action)

    private fun decodeRule(raw: String): GestureRule? {
        val fields = ArrayList<String>(RULE_FIELDS)
        var start = 0
        repeat(RULE_FIELDS) {
            val end = raw.indexOf(SEP, start)
            if (end < 0) return null
            fields += raw.substring(start, end)
            start = end + 1
        }
        val body = raw.substring(start)
        val side = PanelSide.entries.getOrNull(fields[1].toIntOrNull() ?: return null) ?: return null
        val trigger = GestureTriggerType.fromId(fields[3].toIntOrNull() ?: return null) ?: return null
        val typeId = fields[4].toIntOrNull() ?: return null
        val type = GestureActionType.fromId(typeId)
        if (type.id != typeId) return null
        val action = QuickLauncherItemCodec.decodeActionBody(type, body)?.normalized() ?: return null
        return GestureRule(
            id = fields[0],
            side = side,
            trigger = trigger,
            action = action,
            priority = fields[5].toIntOrNull() ?: 0,
            enabled = fields[6] == "1",
            triggerMode = GestureTriggerMode.fromId(fields[7].toIntOrNull() ?: return null),
            handleId = fields[2],
        )
    }

    // ── 数值字段：k=v; 打包（值只可能是数字 / 枚举名） ──────────────────────
    private fun encodeScalars(s: GestureProfileSnapshot): String = buildString {
        fun b(key: String, value: Boolean) = append(key).append('=').append(if (value) '1' else '0').append(';')
        fun f(key: String, value: Float) = append(key).append('=').append(value).append(';')
        fun i(key: String, value: Int) = append(key).append('=').append(value).append(';')

        b("lEdge", s.leftEdgeEnabled)
        b("rEdge", s.rightEdgeEnabled)
        f("lWidth", s.leftEdgeTriggerWidthDp)
        f("rWidth", s.rightEdgeTriggerWidthDp)
        f("bWidth", s.bottomEdgeTriggerWidthDp)
        f("tWidth", s.topEdgeTriggerWidthDp)
        f("lTop", s.leftTriggerTopFraction)
        f("rTop", s.rightTriggerTopFraction)
        f("lHeight", s.leftTriggerHeightFraction)
        f("rHeight", s.rightTriggerHeightFraction)
        b("landInit", s.landscapeTriggersInitialized)
        i("lMode", s.leftDefaultTriggerMode.id)
        i("rMode", s.rightDefaultTriggerMode.id)
        i("bMode", s.bottomDefaultTriggerMode.id)
        i("tMode", s.topDefaultTriggerMode.id)
        i("lModeL", s.leftDefaultTriggerModeLandscape.id)
        i("rModeL", s.rightDefaultTriggerModeLandscape.id)
        i("bModeL", s.bottomDefaultTriggerModeLandscape.id)
        i("tModeL", s.topDefaultTriggerModeLandscape.id)
        f("short", s.shortSwipeDistanceDp)
        f("long", s.longSwipeDistanceDp)
        b("hint", s.gestureHintEnabled)
        i("hintStyle", s.gestureHintStyleId)
        f("hintOffset", s.gestureHintFingerOffsetDp)
        i("backColor", s.androidBackColorSourceId)
        i("hover", s.swipeHoverDurationMs)
        i("dblTap", s.triggerDoubleTapIntervalMs)
        append("kbP").append('=').append(s.keyboardTriggerBehaviorPortrait.storageKey).append(';')
        append("kbL").append('=').append(s.keyboardTriggerBehaviorLandscape.storageKey).append(';')
        i("kbNarrowP", s.keyboardTriggerNarrowPercentPortrait)
        i("kbNarrowL", s.keyboardTriggerNarrowPercentLandscape)
        f("angLP1", s.gestureAngles.left.p1)
        f("angLP2", s.gestureAngles.left.p2)
        f("angLP3", s.gestureAngles.left.p3)
        f("angLP4", s.gestureAngles.left.p4)
        f("angRP1", s.gestureAngles.right.p1)
        f("angRP2", s.gestureAngles.right.p2)
        f("angRP3", s.gestureAngles.right.p3)
        f("angRP4", s.gestureAngles.right.p4)
        f("angBP1", s.gestureAngles.bottom.p1)
        f("angBP2", s.gestureAngles.bottom.p2)
        f("angBP3", s.gestureAngles.bottom.p3)
        f("angBP4", s.gestureAngles.bottom.p4)
        f("angTP1", s.gestureAngles.top.p1)
        f("angTP2", s.gestureAngles.top.p2)
        f("angTP3", s.gestureAngles.top.p3)
        f("angTP4", s.gestureAngles.top.p4)
    }

    private fun parseScalars(raw: String): Map<String, String> = buildMap {
        for (entry in raw.split(';')) {
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            put(entry.substring(0, eq), entry.substring(eq + 1))
        }
    }

    private class Builder(private val id: String) {
        var name: String = ""
        val packages = linkedSetOf<String>()
        var scalars: Map<String, String> = emptyMap()
        var animationStyles: AnimationStyles = AnimationStyles()
        val handles = LinkedHashMap<PanelSide, MutableList<TriggerHandle>>()
        val handlesLandscape = LinkedHashMap<PanelSide, MutableList<TriggerHandle>>()
        val rules = mutableListOf<GestureRule>()
        val rulesLandscape = mutableListOf<GestureRule>()

        fun build(): GestureProfile = GestureProfile(
            id = id,
            name = name,
            packageNames = packages,
            snapshot = decodeSnapshot(
                scalars = scalars,
                animationStyles = animationStyles,
                handles = handles,
                handlesLandscape = handlesLandscape,
                rules = rules,
                rulesLandscape = rulesLandscape,
            ),
        )
    }

    private fun decodeSnapshot(
        scalars: Map<String, String>,
        animationStyles: AnimationStyles,
        handles: Map<PanelSide, List<TriggerHandle>>,
        handlesLandscape: Map<PanelSide, List<TriggerHandle>>,
        rules: List<GestureRule>,
        rulesLandscape: List<GestureRule>,
    ): GestureProfileSnapshot {
        val d = GestureProfileSnapshot()
        fun bool(key: String, fallback: Boolean) = scalars[key]?.let { it == "1" } ?: fallback
        fun float(key: String, fallback: Float) = scalars[key]?.toFloatOrNull() ?: fallback
        fun int(key: String, fallback: Int) = scalars[key]?.toIntOrNull() ?: fallback
        fun mode(key: String, fallback: GestureTriggerMode) =
            scalars[key]?.toIntOrNull()?.let(GestureTriggerMode::fromId) ?: fallback
        fun kb(key: String, fallback: KeyboardTriggerBehavior) =
            scalars[key]?.let(KeyboardTriggerBehavior::fromStorageKey) ?: fallback
        fun angle(prefix: String, fallback: GestureAngle) = GestureAngle(
            p1 = float("${prefix}P1", fallback.p1),
            p2 = float("${prefix}P2", fallback.p2),
            p3 = float("${prefix}P3", fallback.p3),
            p4 = float("${prefix}P4", fallback.p4),
        )
        return GestureProfileSnapshot(
            leftEdgeEnabled = bool("lEdge", d.leftEdgeEnabled),
            rightEdgeEnabled = bool("rEdge", d.rightEdgeEnabled),
            leftEdgeTriggerWidthDp = float("lWidth", d.leftEdgeTriggerWidthDp),
            rightEdgeTriggerWidthDp = float("rWidth", d.rightEdgeTriggerWidthDp),
            bottomEdgeTriggerWidthDp = float("bWidth", d.bottomEdgeTriggerWidthDp),
            topEdgeTriggerWidthDp = float("tWidth", d.topEdgeTriggerWidthDp),
            leftTriggerTopFraction = float("lTop", d.leftTriggerTopFraction),
            rightTriggerTopFraction = float("rTop", d.rightTriggerTopFraction),
            leftTriggerHeightFraction = float("lHeight", d.leftTriggerHeightFraction),
            rightTriggerHeightFraction = float("rHeight", d.rightTriggerHeightFraction),
            leftTriggerHandles = handles[PanelSide.LEFT]?.takeIf { it.isNotEmpty() } ?: d.leftTriggerHandles,
            rightTriggerHandles = handles[PanelSide.RIGHT]?.takeIf { it.isNotEmpty() } ?: d.rightTriggerHandles,
            bottomTriggerHandles = handles[PanelSide.BOTTOM]?.takeIf { it.isNotEmpty() } ?: d.bottomTriggerHandles,
            topTriggerHandles = handles[PanelSide.TOP]?.takeIf { it.isNotEmpty() } ?: d.topTriggerHandles,
            leftTriggerHandlesLandscape = handlesLandscape[PanelSide.LEFT].orEmpty(),
            rightTriggerHandlesLandscape = handlesLandscape[PanelSide.RIGHT].orEmpty(),
            bottomTriggerHandlesLandscape = handlesLandscape[PanelSide.BOTTOM].orEmpty(),
            topTriggerHandlesLandscape = handlesLandscape[PanelSide.TOP].orEmpty(),
            landscapeTriggersInitialized = bool("landInit", d.landscapeTriggersInitialized),
            // 一条规则都没解出来（记录缺失/整条坏掉）时回落到出厂默认，与全局空的处理一致。
            gestureRules = rules.takeIf { it.isNotEmpty() } ?: d.gestureRules,
            gestureRulesLandscape = rulesLandscape.takeIf { it.isNotEmpty() } ?: d.gestureRulesLandscape,
            leftDefaultTriggerMode = mode("lMode", d.leftDefaultTriggerMode),
            rightDefaultTriggerMode = mode("rMode", d.rightDefaultTriggerMode),
            bottomDefaultTriggerMode = mode("bMode", d.bottomDefaultTriggerMode),
            topDefaultTriggerMode = mode("tMode", d.topDefaultTriggerMode),
            leftDefaultTriggerModeLandscape = mode("lModeL", d.leftDefaultTriggerModeLandscape),
            rightDefaultTriggerModeLandscape = mode("rModeL", d.rightDefaultTriggerModeLandscape),
            bottomDefaultTriggerModeLandscape = mode("bModeL", d.bottomDefaultTriggerModeLandscape),
            topDefaultTriggerModeLandscape = mode("tModeL", d.topDefaultTriggerModeLandscape),
            shortSwipeDistanceDp = float("short", d.shortSwipeDistanceDp),
            longSwipeDistanceDp = float("long", d.longSwipeDistanceDp),
            gestureHintEnabled = bool("hint", d.gestureHintEnabled),
            gestureHintStyleId = int("hintStyle", d.gestureHintStyleId),
            gestureHintFingerOffsetDp = float("hintOffset", d.gestureHintFingerOffsetDp),
            androidBackColorSourceId = int("backColor", d.androidBackColorSourceId),
            swipeHoverDurationMs = int("hover", d.swipeHoverDurationMs),
            triggerDoubleTapIntervalMs = int("dblTap", d.triggerDoubleTapIntervalMs),
            animationStyles = animationStyles,
            gestureAngles = GestureAngles(
                left = angle("angL", d.gestureAngles.left),
                right = angle("angR", d.gestureAngles.right),
                bottom = angle("angB", d.gestureAngles.bottom),
                top = angle("angT", d.gestureAngles.top),
            ),
            keyboardTriggerBehaviorPortrait = kb("kbP", d.keyboardTriggerBehaviorPortrait),
            keyboardTriggerBehaviorLandscape = kb("kbL", d.keyboardTriggerBehaviorLandscape),
            keyboardTriggerNarrowPercentPortrait = int("kbNarrowP", d.keyboardTriggerNarrowPercentPortrait),
            keyboardTriggerNarrowPercentLandscape = int("kbNarrowL", d.keyboardTriggerNarrowPercentLandscape),
        )
    }
}

/** 某个 `AppSettings` 的触钮集合快照（"恢复为全局"与"建档复制"共用）。 */
fun AppSettings.gestureSnapshot(): GestureProfileSnapshot = GestureProfileSnapshot.from(this)

/** 一个档内、指定触钮组在该侧的动作是否与全局不同（UI 标「已覆盖 / 跟随全局」用）。 */
fun GestureProfileSnapshot.rulesFor(side: PanelSide, landscape: Boolean): List<GestureRule> =
    if (landscape) gestureRulesLandscape.filter { it.side == side } else gestureRules.filter { it.side == side }

/** 该档里某个触钮组（侧 + handleId + 竖/横屏）的动作表。 */
fun GestureProfileSnapshot.actionsOfHandle(
    side: PanelSide,
    handleId: String,
    landscape: Boolean,
): Map<GestureTriggerType, GestureAction> = rulesFor(side, landscape)
    .filter { it.handleId == handleId }
    .associate { it.trigger to it.action }

/** 某个触钮组是否在档里被改过（与全局同组动作表逐项比较，只比两边都存在的触发类型）。 */
fun GestureProfileSnapshot.handleDiffersFromGlobal(
    global: GestureProfileSnapshot,
    side: PanelSide,
    handleId: String,
    landscape: Boolean,
): Boolean {
    val mine = actionsOfHandle(side, handleId, landscape)
    val theirs = global.actionsOfHandle(side, handleId, landscape)
    if (mine.keys != theirs.keys) return true
    return mine.any { (trigger, action) -> theirs[trigger] != action }
}

/** 触钮组几何/外观是否与全局不同。 */
fun GestureProfileSnapshot.handleGeometryDiffersFromGlobal(
    global: GestureProfileSnapshot,
    side: PanelSide,
    landscape: Boolean,
): Boolean = handlesFor(side, landscape) != global.handlesFor(side, landscape)
