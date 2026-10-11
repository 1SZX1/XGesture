package com.slideindex.app.overlay.history

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slideindex.app.R
import com.slideindex.app.settings.StashPanelWidthMode
import com.slideindex.app.stash.StashMetaRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.text.TextStyle

/** 顶栏工具图标：与 [MiuixExpandableSearchIconAction] 同尺寸、同色。 */
@Composable
internal fun HistoryPanelToolbarIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}

/**
 * 浮窗卡片本体"吃点击"（**空实现，只消费**）。
 *
 * ⚠️ 没有它，点在卡片上的**空白处**（内边距 / 行间距 / 纯文字区）会穿到下面那层
 * "点空白关闭"的遮罩上 —— 用户实测：手指明明落在弹窗里，弹窗却被关掉了。
 * 面板自己早就这么干了（`HistoryPanelScreen` 里面板根节点那个空 `clickable {}`），
 * 三块浮窗当时漏了这一步。
 */
@Composable
internal fun Modifier.historyConsumeTaps(): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
) {}

/** 对齐设计稿：正文 `--f-sm` 12.5px、行高 1.6；元信息 `--f-tiny` 10.5px。 */
internal object HistoryPanelTypography {
    @Composable
    fun content(): TextStyle = MiuixTheme.textStyles.body2.copy(
        fontSize = 13.sp,
        lineHeight = 21.sp,
    )

    @Composable
    fun meta(): TextStyle = MiuixTheme.textStyles.footnote2

    @Composable
    fun hint(): TextStyle = MiuixTheme.textStyles.footnote1
}

/** 收纳面板列表滚动：MIUI 回弹 + 触底触感。 */
internal fun Modifier.historyPanelListScrollEffects(): Modifier = this
    .scrollEndHaptic()
    .overScrollVertical(nestedScrollToParent = false)

/** 列表参与顶栏毛玻璃采样。 */
internal fun Modifier.historyPanelListBackdrop(backdrop: LayerBackdrop?): Modifier =
    historyPanelListScrollEffects().then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)

/**
 * 列表尾部留给右下角 FAB 的空白（只有闪念页签有 FAB）。
 *
 * 54dp（FAB 本体）+ 32dp（它现在的底边偏移）+ 10dp 余量 = 96dp —— 之前是 64dp，
 * 那是 FAB 还贴着屏幕底边时定的，结果最后一张卡的 ⋮ 会被 FAB 压住。
 */
internal val HistoryListFooterPadding = 96.dp

/**
 * 当前面板宽度比例，由 `FloatBallStashPanel.PanelContent` 按设置注入（见 [StashPanelWidthMode]）。
 *
 * 用 CompositionLocal 而不是给 [panelWidthOf] 加参数：吃这个值的地方 —— 面板本体宽度、
 * 图片降采样宽（[historyPreviewWidthPx]）、卡片缩略图宽（`HistoryEntryCards`）—— 散落在两个
 * 没有 settings 的工具函数和一个卡片组件里，一路传参要动一串签名。
 */
internal val LocalStashPanelWidthFraction = staticCompositionLocalOf {
    StashPanelWidthMode.STANDARD.fraction
}

/**
 * 面板宽度 = 可用宽度 × 当前档位比例，**再用 [PANEL_WIDTH_MAX] 封顶**。
 *
 * ⚠️ 别把它当"已废弃"（老注释就是这么写的，是 §0.16.3 那次"窗口改 78% 宽"实验的残留）：
 * §0.16.3 已被用户整批回退 —— 现在**窗口是满屏的**（`OverlayPanelLayoutParams`），
 * 面板内容由 `HistoryPanelScreen` 用 `BoxWithConstraints` 的 `maxWidth` 乘出这个宽度，
 * 所以这里就是**生产路径**。
 */
@Composable
internal fun panelWidthOf(available: Dp): Dp =
    minOf(available * LocalStashPanelWidthFraction.current, PANEL_WIDTH_MAX)

/**
 * 面板宽度上限（dp）—— 治"横屏也占 78%"。
 *
 * 竖屏 411dp × 78% = 320dp，够不着上限，**行为与设计稿一致、没有变化**；
 * 横屏窗口宽 891dp，78% 会变成 695dp（几乎占满整屏，用户实测反馈"横屏不该也 78%"），
 * 于是被收到 420dp ≈ **横屏的小半个屏幕**。平板/折叠屏展开态同理受益。
 */
internal val PANEL_WIDTH_MAX = 420.dp

/**
 * 跟手拉出时"拉多远算拉满" = 面板宽度（px）。
 *
 * 面板侧用的是布局约束（见上），但**把手窗只有 48dp 宽**，它读不到屏幕宽度，
 * 所以把手侧用窗口管理器的**当前窗口度量**（`currentWindowMetrics`，随旋转更新；
 * `resources.displayMetrics` 在 overlay 里可能是陈旧的旋转前尺寸）。
 */
internal fun historyPanelRevealDistancePx(context: android.content.Context): Float =
    // 设计稿 `revealStream(dx) { const p = clamp(-dx / 130) }`：**手指横向拖 130px 就拉满**，
    // 和面板宽度无关。我原先按"面板宽度(842px)"算，于是要拖满一屏才到位、手感完全不对。
    (PANEL_REVEAL_DRAG_DP * context.resources.displayMetrics.density)

/** 设计稿里"拉满"所需的手指位移（`-dx / 130`）。 */
internal const val PANEL_REVEAL_DRAG_DP = 130

/** 设计稿里侧栏的圆角（`--r-2xl` = 26dp）。 */
internal val HistoryPanelCornerRadius = 26.dp

@Composable
internal fun historyClipboardCardPreviewHeightPx(): Int {
    val density = LocalDensity.current
    return with(density) { 120.dp.roundToPx() }
}

@Composable
internal fun historyPreviewWidthPx(): Int {
    val density = LocalDensity.current
    // 预览只用来给图片解码降采样。面板 = **窗口宽 × 78%**（窗口是满屏的，见 `OverlayPanelLayoutParams`）。
    // `containerSize` 在真机上可能返回旋转过的尺寸，取 min(w,h) 兜底；上限 960px。
    val windowWidth = LocalWindowInfo.current.containerSize.let { size ->
        if (size.width <= 0) size.height else minOf(size.width, size.height)
    }
    return with(density) {
        // 与面板宽度同一个算式（含 420dp 上限），图片解码宽度才不会和真实面板宽度跑偏。
        (panelWidthOf(with(density) { windowWidth.toDp() }) - 24.dp)
            .roundToPx()
            .coerceAtMost(960)
    }
}

@Composable
internal fun historyStashPreviewHeightPx(): Int {
    val density = LocalDensity.current
    return with(density) { 150.dp.roundToPx() }
}

@Composable
internal fun historyExpandedImageMaxSidePx(): Int {
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val screenHeightPx = windowInfo.containerSize.height
    return with(density) {
        maxOf(
            historyPreviewWidthPx(),
            (screenHeightPx * 0.75f).toInt(),
        ).coerceAtMost(2048)
    }
}

@Composable
internal fun formatHistoryRelativeTime(epochMs: Long): String {
    val diffMs = (System.currentTimeMillis() - epochMs).coerceAtLeast(0L)
    return when {
        diffMs < 60_000L -> stringResource(R.string.stash_time_just_now)
        diffMs < 3_600_000L -> stringResource(R.string.stash_time_minutes_ago, (diffMs / 60_000L).toInt())
        diffMs < 86_400_000L -> stringResource(R.string.stash_time_hours_ago, (diffMs / 3_600_000L).toInt())
        else -> {
            val locale = LocalLocale.current.platformLocale
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = epochMs }
            val pattern = if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
                if (locale.language == "zh") "M月d日" else "MMM d"
            } else {
                "yyyy/M/d"
            }
            SimpleDateFormat(pattern, locale).format(Date(epochMs))
        }
    }
}

/**
 * 提醒时间的展示（设计稿 `.item .remind`）：`今天 21:30` / `明天 09:00` / `昨天 21:30` / `3月5日 09:00`。
 *
 * 与 [formatHistoryRelativeTime] 不同：提醒按"今天/明天/日期"说。
 *
 * ⚠️ 这个函数**也服务于过去**：灰色「已提醒」画的是"这条提醒什么时候到点过"
 * （`firedAt` 记的是**提醒原本的到点时刻**，不是"我们看见它的时刻"），所以昨天到点的会走到「昨天」那一档。
 * 没有这一档时它会退化成 `10月8日 21:30` 这种绝对日期 —— 能看，但不如"昨天 21:30"直观。
 */
@Composable
internal fun formatReminderTime(epochMs: Long): String {
    val locale = LocalLocale.current.platformLocale
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = epochMs }
    val time = SimpleDateFormat("HH:mm", locale).format(Date(epochMs))
    val dayLabel = when {
        sameDay(now, target) -> stringResource(R.string.stash_group_today)
        // 「昨天」：给**响过**的提醒用（见上面 KDoc —— `firedAt` 记的是到点时刻）。
        // 复用分组那枚 key（四个 locale 都已存在：昨天 / Yesterday / 昨日 / أمس），不新增字符串。
        sameDay(target, Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }) -> {
            stringResource(R.string.stash_group_yesterday)
        }
        // ⚠️ 这里必须拿 **target** 和"明天"比：原来写成 `sameDay(now, 明天)` 是拿今天比明天，恒为 false，
        // 于是"明天 09:00"一直退化成"10月9日 09:00"（§0.16.10 修）。
        sameDay(target, Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }) -> {
            stringResource(R.string.stash_remind_tomorrow)
        }
        else -> {
            val pattern = if (locale.language == "zh") "M月d日" else "MMM d"
            SimpleDateFormat(pattern, locale).format(Date(epochMs))
        }
    }
    return "$dayLabel $time"
}

private fun sameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

/**
 * 默认提醒时间：**明天 09:00**。
 *
 * 设计稿里「提醒」按钮就是这个行为（`s.remind = s.remind ? null : '明天 09:00'`）——
 * 真正的选择器是后续的事，先把"能设、看得见、到点会响"这条链打通。
 */
internal fun historyDefaultReminderAt(): Long =
    Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 9)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/**
 * 卡片底部那枚「来源」chip 的文案（设计稿 `.foot` 里的 `from`）。
 *
 * 认不出来的来源键返回 null = 不显示 chip。数据层刻意把来源存成**字符串键**，
 * 就是为了让"新版本写了我们还不知道的来源"最多少显示一枚 chip，而不是解码整个文件失败。
 */
@StringRes
internal fun stashSourceLabelRes(sourceKey: String?): Int? = when (sourceKey) {
    StashMetaRepository.SOURCE_CLIPBOARD -> R.string.stash_source_clipboard
    StashMetaRepository.SOURCE_PICK -> R.string.stash_source_pick
    StashMetaRepository.SOURCE_IMAGE -> R.string.stash_source_image
    else -> null
}

/**
 * 五枚**默认标签**的显示名资源（返回 null = 不是默认标签，按原名显示）。
 *
 * ⚠️ 内部名仍然是中文（[StashMetaRepository.DEFAULT_TAGS] / [StashMetaRepository.TODO_TAG_NAME]）：
 * 标签名是**存储键**（`assignments` 是 `{条目 → [标签名]}`），跟着语言变会让绑定与"这条是不是
 * 待办"的判定分叉。所以只把**显示**这一层本地化 —— 用户自己改过名的标签名字不再匹配，
 * 会自然回落到原名，不会误伤。
 *
 * ⚠️ 以后新增/改默认标签名，**必须**在这里挂上资源，否则那枚标签会在英文/日文/阿文界面显示
 * 中文。这条约束有单测守着（`DefaultTagLabelTest`）。
 */
@StringRes
internal fun defaultTagLabelRes(name: String): Int? = when (name) {
    StashMetaRepository.WORK_TAG_NAME -> R.string.stash_tag_default_work
    StashMetaRepository.IDEA_TAG_NAME -> R.string.stash_tag_default_idea
    StashMetaRepository.TODO_TAG_NAME -> R.string.stash_tag_default_todo
    StashMetaRepository.INSPIRATION_TAG_NAME -> R.string.stash_tag_default_inspiration
    StashMetaRepository.DESIGN_TAG_NAME -> R.string.stash_tag_default_design
    else -> null
}

/** 标签的**显示名**：默认标签走资源（当前语言），其余原样。 */
@Composable
internal fun stashTagLabel(name: String): String =
    defaultTagLabelRes(name)?.let { stringResource(it) } ?: name
