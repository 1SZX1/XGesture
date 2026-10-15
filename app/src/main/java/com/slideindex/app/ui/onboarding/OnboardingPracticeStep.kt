package com.slideindex.app.ui.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slideindex.app.R
import com.slideindex.app.gesture.GestureTriggerMode
import com.slideindex.app.gesture.GestureTriggerType
import com.slideindex.app.overlay.PanelSide
import com.slideindex.app.settings.OverlaySettings
import com.slideindex.app.settings.SettingsRepository
// 用非 Composable 的 gestureActionLabelText：浮层路径没有 LocalAppDependencies，
// 而 Composable 版会去取 AppRepository。
import com.slideindex.app.ui.gesturepicker.gestureActionLabelText
import com.slideindex.app.ui.miuix.theme.ModuleTheme
import com.slideindex.app.ui.miuix.theme.toModuleThemeSettings
import com.slideindex.app.ui.theme.LocalAppDarkTheme
import com.slideindex.app.ui.triggerLabel
import com.slideindex.app.ui.triggerModeLabel
import com.slideindex.app.util.PermissionHelper
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val SuccessGreen = Color(0xFF22C55E)
private val BrandPurple = Color(0xFF7256B8)
private val BrandPurpleLight = Color(0xFF9881D4)

/**
 * 新手引导第 4 步「上手两件事」的内容区。
 *
 * 只做两件事：把「悬浮指针」绑到边缘触钮、把悬浮球打开并取一次词。
 * 骨架与第 2/3 步保持一致（同款卡片、底部按钮），底栏由调用方注入，
 * 这样引导流程内（上一步 / 完成）与「重看引导」浮层（关闭）能共用同一份内容。
 */
@Composable
fun OnboardingPracticeContent(
    controller: OnboardingPracticeController,
    onRequestAccessibility: () -> Unit,
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalAppDarkTheme.current

    // 服务连接状态、以及别处改设置都要跟着刷新；1s 一次足够，且比订阅多条设置流更便宜。
    LaunchedEffect(controller) {
        // 打开引导：把真实触钮亮到屏幕上（用户才知道该按哪儿），并顺手修一次规则表。
        controller.beginSession()
        while (true) {
            controller.refresh()
            delay(1_000)
        }
    }
    val hintRes = controller.hintRes
    LaunchedEffect(hintRes) {
        if (hintRes != null) {
            delay(5_000)
            controller.consumeHint()
        }
    }

    val pointerState = controller.pointerState
    val ballState = controller.ballState
    val indexState = controller.indexState
    val waitingKind = when {
        controller.indexHintActive -> PracticeIllustrationKind.EDGE_INDEX_DOWN
        pointerState == PracticeTaskState.RUNNING -> PracticeIllustrationKind.EDGE_POINTER
        else -> null
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.onboarding_practice_title),
                style = MiuixTheme.textStyles.title1.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            Text(
                text = stringResource(R.string.onboarding_practice_subtitle),
                style = MiuixTheme.textStyles.body2.copy(lineHeight = 20.sp),
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )

            // 只在"根本没授权"时提示；服务实例的抖动交给 App 自己的自愈去处理，
            // 否则用户会看到提示条每隔十几秒闪一次。
            if (!controller.serviceEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                ServiceNotice(onRequestAccessibility = onRequestAccessibility)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // —— 卡 1：出厂就有的两个手势。放在最前面：这是装上就能用的东西，
            // 先确认"最基础的返回已经能用"，再去配需要动手的两件新东西。 ——
            PracticeCard(
                index = 1,
                title = stringResource(R.string.onboarding_practice_defaults_title),
                subtitle = stringResource(R.string.onboarding_practice_defaults_subtitle),
                state = indexState,
                isDark = isDark,
            ) {
                GestureFactRow(
                    kind = PracticeIllustrationKind.EDGE_BACK,
                    name = stringResource(R.string.onboarding_practice_back_gesture_name),
                    body = stringResource(R.string.onboarding_practice_back_gesture_body),
                )
                Spacer(modifier = Modifier.height(12.dp))
                GestureFactRow(
                    kind = PracticeIllustrationKind.EDGE_INDEX_DOWN,
                    name = stringResource(R.string.onboarding_practice_index_gesture_name),
                    body = stringResource(R.string.onboarding_practice_index_gesture_body),
                )
                Spacer(modifier = Modifier.height(12.dp))
                PracticeCardActions(
                    state = indexState,
                    busy = controller.indexBusy,
                    primaryEnabled = true,
                    primaryPendingRes = R.string.onboarding_practice_index_try,
                    primaryReadyRes = R.string.onboarding_practice_index_try,
                    primaryDoneRes = R.string.onboarding_practice_try_again,
                    secondaryReadyRes = null,
                    // 只负责把"按住这里"的标记亮出来；打勾由常驻的手势监听决定。
                    onPrimary = { controller.showIndexHint() },
                    onSecondary = {},
                    onCancel = { controller.cancelPendingTests() },
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // —— 卡 2：触钮 → 悬浮指针 ——
            PracticeCard(
                index = 2,
                title = stringResource(R.string.onboarding_practice_pointer_title),
                subtitle = stringResource(R.string.onboarding_practice_pointer_body),
                state = pointerState,
                isDark = isDark,
            ) {
                PracticeIllustration(
                    kind = PracticeIllustrationKind.EDGE_POINTER,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        R.string.onboarding_practice_pointer_config,
                        stringResource(
                            if (controller.pointerSide == PanelSide.LEFT) {
                                R.string.onboarding_practice_side_left
                            } else {
                                R.string.onboarding_practice_side_right
                            },
                        ),
                        qualifiedTriggerLabel(controller.pointerSide, controller.pointerTrigger),
                        triggerModeLabel(GestureTriggerMode.CONTINUOUS),
                    ),
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp),
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
                if (pointerState == PracticeTaskState.DONE) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.onboarding_practice_pointer_done),
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        color = SuccessGreen,
                    )
                }
                // 顶掉了出厂动作就明确告诉用户"搬到哪去了"——覆盖行为不能是暗的。
                val relocatedAction = controller.relocatedAction
                val relocatedTrigger = controller.relocatedTrigger
                if (relocatedAction != null && relocatedTrigger != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.onboarding_practice_pointer_moved,
                            gestureActionLabelText(LocalContext.current, relocatedAction),
                            qualifiedTriggerLabel(controller.pointerSide, relocatedTrigger),
                        ),
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp),
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                PracticeCardActions(
                    state = pointerState,
                    busy = controller.pointerBusy,
                    primaryEnabled = true,
                    primaryPendingRes = R.string.onboarding_practice_pointer_bind,
                    primaryReadyRes = R.string.onboarding_practice_demo,
                    primaryDoneRes = R.string.onboarding_practice_demo_again,
                    secondaryReadyRes = R.string.onboarding_practice_try_myself,
                    onPrimary = {
                        when (pointerState) {
                            PracticeTaskState.PENDING -> controller.bindPointer()
                            PracticeTaskState.RUNNING -> Unit
                            else -> controller.demoPointer()
                        }
                    },
                    onSecondary = { controller.awaitPointerTest() },
                    onCancel = { controller.cancelPendingTests() },
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // —— 卡 3（原卡 2）：悬浮球取词 ——
            val pickedText = controller.pickedText
            PracticeCard(
                index = 3,
                title = stringResource(R.string.onboarding_practice_ball_title),
                subtitle = stringResource(R.string.onboarding_practice_ball_body),
                state = ballState,
                isDark = isDark,
            ) {
                PracticeIllustration(
                    kind = PracticeIllustrationKind.BALL_PICK,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = pickedText
                        ?.let { stringResource(R.string.onboarding_practice_ball_picked, it) }
                        ?: stringResource(R.string.onboarding_practice_ball_config),
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp),
                    color = if (pickedText != null) {
                        SuccessGreen
                    } else {
                        MiuixTheme.colorScheme.onSurfaceSecondary
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
                PracticeCardActions(
                    state = ballState,
                    busy = controller.ballBusy,
                    primaryEnabled = true,
                    primaryPendingRes = R.string.onboarding_practice_ball_enable,
                    primaryReadyRes = R.string.onboarding_practice_try_myself,
                    primaryDoneRes = R.string.onboarding_practice_try_again,
                    secondaryReadyRes = null,
                    onPrimary = {
                        when (ballState) {
                            PracticeTaskState.PENDING -> controller.enableFloatBall()
                            PracticeTaskState.RUNNING -> Unit
                            else -> controller.awaitPickTest()
                        }
                    },
                    onSecondary = {},
                    onCancel = { controller.cancelPendingTests() },
                )
            }

            if (hintRes != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(hintRes),
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }

            bottomBar()
            Spacer(modifier = Modifier.height(20.dp))
        }

        // 标记必须画在内容**之后**：卡片是不透明白底，先画会被整片盖住（实测点完按钮什么都看不见）。
        // 位置对齐真实触钮那一段纵向范围，等待手势时呼吸闪动。
        EdgeGestureHint(
            side = controller.pointerSide,
            topFraction = controller.triggerTopFraction,
            heightFraction = controller.triggerHeightFraction,
            kind = waitingKind,
            modifier = Modifier.align(
                if (controller.pointerSide == PanelSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd,
            ),
        )
    }
}

/** 「重看新手引导」浮层：整屏盖住导航宿主，主题与引导页保持一致。 */
@Composable
fun OnboardingPracticeOverlay(
    settingsRepository: SettingsRepository,
    onPreviewTriggerBar: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val overlayUiSettings by settingsRepository.overlaySettings.collectAsStateWithLifecycle(
        initialValue = OverlaySettings.from(settingsRepository.readSnapshot()),
    )
    val scope = rememberCoroutineScope()
    val controller = remember(settingsRepository, scope) {
        OnboardingPracticeController(
            context = context.applicationContext,
            settingsRepository = settingsRepository,
            scope = scope,
            onPreviewTriggerBar = onPreviewTriggerBar,
        )
    }
    val insetsPadding = WindowInsets.systemBars
        .add(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Vertical + WindowInsetsSides.Horizontal)
        .asPaddingValues()

    ModuleTheme(settings = overlayUiSettings.toModuleThemeSettings()) {
        val isDark = LocalAppDarkTheme.current
        // 浮层不在导航栈里，系统返回键要自己吃掉，否则会pop掉下面那一页。
        BackHandler(enabled = true) {
            controller.release()
            onClose()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isDark) Color(0xFF121216) else Color(0xFFF6F5FA))
                .padding(insetsPadding),
        ) {
            OnboardingPracticeContent(
                controller = controller,
                onRequestAccessibility = {
                    context.startActivity(PermissionHelper.accessibilitySettingsIntent())
                },
                bottomBar = {
                    Button(
                        onClick = {
                            controller.release()
                            onClose()
                        },
                        cornerRadius = 18.dp,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_practice_close),
                            style = MiuixTheme.textStyles.button,
                            color = MiuixTheme.colorScheme.onPrimary,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun ServiceNotice(
    onRequestAccessibility: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.onboarding_practice_service_off),
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(
            text = stringResource(R.string.onboarding_practice_service_open),
            onClick = onRequestAccessibility,
            cornerRadius = 12.dp,
            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
private fun PracticeCard(
    index: Int,
    title: String,
    subtitle: String,
    state: PracticeTaskState,
    isDark: Boolean,
    content: @Composable () -> Unit,
) {
    Card(
        cornerRadius = 24.dp,
        insideMargin = PaddingValues(18.dp),
        colors = CardDefaults.defaultColors(
            color = if (isDark) Color(0xFF1E1E24).copy(alpha = 0.95f) else Color.White.copy(alpha = 0.95f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(24.dp), spotColor = Color(0x14000000)),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(BrandPurpleLight.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = index.toString(),
                        style = MiuixTheme.textStyles.title3.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        color = BrandPurple,
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MiuixTheme.textStyles.title3.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = subtitle,
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                PracticeStateChip(state)
            }
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun PracticeStateChip(state: PracticeTaskState) {
    val color = when (state) {
        PracticeTaskState.DONE -> SuccessGreen
        PracticeTaskState.RUNNING -> BrandPurpleLight
        PracticeTaskState.READY -> BrandPurple
        PracticeTaskState.PENDING -> MiuixTheme.colorScheme.onSurfaceSecondary
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state == PracticeTaskState.DONE) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = stringResource(
                when (state) {
                    PracticeTaskState.PENDING -> R.string.onboarding_practice_state_pending
                    PracticeTaskState.READY -> R.string.onboarding_practice_state_ready
                    PracticeTaskState.RUNNING -> R.string.onboarding_practice_state_running
                    PracticeTaskState.DONE -> R.string.onboarding_practice_state_done
                },
            ),
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
            color = color,
        )
    }
}

@Composable
private fun PracticeCardActions(
    state: PracticeTaskState,
    busy: Boolean,
    primaryEnabled: Boolean,
    primaryPendingRes: Int,
    primaryReadyRes: Int,
    primaryDoneRes: Int,
    secondaryReadyRes: Int?,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state == PracticeTaskState.RUNNING) {
            Button(
                onClick = onCancel,
                cornerRadius = 14.dp,
                colors = ButtonDefaults.buttonColors(
                    color = MiuixTheme.colorScheme.surfaceVariant,
                    contentColor = MiuixTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_practice_cancel),
                    style = MiuixTheme.textStyles.button,
                )
            }
        } else {
            Button(
                onClick = onPrimary,
                enabled = !busy && primaryEnabled,
                cornerRadius = 14.dp,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) {
                Text(
                    text = stringResource(
                        when (state) {
                            PracticeTaskState.PENDING -> primaryPendingRes
                            PracticeTaskState.DONE -> primaryDoneRes
                            else -> primaryReadyRes
                        },
                    ),
                    style = MiuixTheme.textStyles.button,
                    color = if (!busy && primaryEnabled) {
                        MiuixTheme.colorScheme.onPrimary
                    } else {
                        MiuixTheme.colorScheme.disabledOnPrimaryButton
                    },
                )
            }
            if (state == PracticeTaskState.READY && secondaryReadyRes != null) {
                TextButton(
                    text = stringResource(secondaryReadyRes),
                    onClick = onSecondary,
                    cornerRadius = 14.dp,
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

private enum class PracticeIllustrationKind { EDGE_POINTER, BALL_PICK, EDGE_BACK, EDGE_INDEX_DOWN }

/**
 * 屏幕上「按住这里」的标记。
 *
 * 贴在**真实触钮的纵向范围**上（用权重定位，不需要测量），这样用户能对着它按。
 * [kind] 非空 = 正在等用户做手势，此时标记呼吸闪动并给出方向文案。
 */
@Composable
private fun EdgeGestureHint(
    side: PanelSide,
    topFraction: Float,
    heightFraction: Float,
    kind: PracticeIllustrationKind?,
    modifier: Modifier = Modifier,
) {
    if (kind == null) return
    val transition = rememberInfiniteTransition(label = "edgeHint")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
        label = "pulse",
    )
    val accent = BrandPurpleLight
    val isLeft = side == PanelSide.LEFT
    val top = topFraction.coerceIn(0f, 0.88f)
    val height = heightFraction.coerceIn(0.06f, 0.88f)
    val bottom = (1f - top - height).coerceAtLeast(0.02f)
    val hintText = stringResource(
        when (kind) {
            PracticeIllustrationKind.EDGE_INDEX_DOWN -> R.string.onboarding_practice_wait_index
            else -> R.string.onboarding_practice_wait_pointer
        },
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(176.dp)
            .padding(vertical = 6.dp),
        horizontalAlignment = if (isLeft) Alignment.Start else Alignment.End,
    ) {
        Spacer(modifier = Modifier.weight(top))
        Row(
            modifier = Modifier.weight(height),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isLeft) Arrangement.Start else Arrangement.End,
        ) {
            if (isLeft) {
                EdgeHintStrip(accent, pulse)
                Spacer(modifier = Modifier.width(8.dp))
                EdgeHintChip(hintText, accent, pulse, isLeft)
            } else {
                EdgeHintChip(hintText, accent, pulse, isLeft)
                Spacer(modifier = Modifier.width(8.dp))
                EdgeHintStrip(accent, pulse)
            }
        }
        Spacer(modifier = Modifier.weight(bottom))
    }
}

@Composable
private fun EdgeHintStrip(accent: Color, pulse: Float) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(7.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(accent.copy(alpha = 0.30f + 0.45f * pulse)),
    )
}

@Composable
private fun EdgeHintChip(text: String, accent: Color, pulse: Float, isLeft: Boolean) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp, lineHeight = 15.sp),
        color = MiuixTheme.colorScheme.onSurface,
        textAlign = if (isLeft) TextAlign.Start else TextAlign.End,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.14f + 0.20f * pulse))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** 卡 3 的一行：小示意图 + 手势名 + 说明（不可点，纯告知）。 */
@Composable
private fun GestureFactRow(
    kind: PracticeIllustrationKind,
    name: String,
    body: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PracticeIllustration(
            kind = kind,
            modifier = Modifier
                .size(width = 96.dp, height = 68.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MiuixTheme.textStyles.title3.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = body,
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
        }
    }
}

/**
 * 触发类型的界面名不带距离（短斜下滑和长斜下滑在右侧都叫「向左下滑动」），
 * 引导里必须补上"短/长"，否则用户分不清要划哪一根。
 */
@Composable
private fun qualifiedTriggerLabel(side: PanelSide, trigger: GestureTriggerType): String {
    val label = triggerLabel(side, trigger)
    return stringResource(
        if (trigger.isLongDistance) {
            R.string.onboarding_practice_distance_long
        } else {
            R.string.onboarding_practice_distance_short
        },
        label,
    )
}

/**
 * 卡片内的方向示意动画。
 *
 * 刻意画在这里而不是在真实屏幕上做手势注入：合成触摸能否被自己的边缘触钮窗口吃到取决于窗口层级
 * 与系统策略，不适合当演示底座。真实结果由「演示一次」直接触发指针给出，这里只负责讲清方向。
 */
@Composable
private fun PracticeIllustration(
    kind: PracticeIllustrationKind,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "practiceIllustration")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "progress",
    )
    val surface = MiuixTheme.colorScheme.surfaceVariant
    val accent = BrandPurpleLight
    val textLineColor = MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.28f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = surface.copy(alpha = 0.55f),
            topLeft = Offset(0f, 0f),
            size = Size(w, h),
            cornerRadius = CornerRadius(18f, 18f),
        )
        when (kind) {
            PracticeIllustrationKind.EDGE_POINTER -> {
                val edgeX = w - 10f
                val topY = h * 0.18f
                val barHeight = h * 0.64f
                drawRoundRect(
                    color = accent.copy(alpha = 0.55f),
                    topLeft = Offset(edgeX - 5f, topY),
                    size = Size(10f, barHeight),
                    cornerRadius = CornerRadius(5f, 5f),
                )
                // 0~0.6 沿"斜向内下"把指针拖出来（与实际绑定的长斜下滑方向一致），随后停留并循环
                val travel = (progress / 0.6f).coerceIn(0f, 1f)
                val startX = edgeX - 5f
                val startY = h * 0.26f
                val endX = w * 0.34f
                val endY = h * 0.78f
                val headX = startX + (endX - startX) * travel
                val headY = startY + (endY - startY) * travel
                drawLine(
                    color = accent.copy(alpha = 0.35f * (1f - travel * 0.3f)),
                    start = Offset(startX, startY),
                    end = Offset(headX, headY),
                    strokeWidth = 3f,
                )
                if (travel > 0.05f) {
                    drawCircle(color = accent, radius = 13f, center = Offset(headX, headY))
                    drawCircle(color = surface, radius = 5f, center = Offset(headX, headY))
                }
            }

            // 短距离内滑＝返回：最短的一条水平内滑
            PracticeIllustrationKind.EDGE_BACK -> {
                val edgeX = w - 10f
                drawRoundRect(
                    color = accent.copy(alpha = 0.55f),
                    topLeft = Offset(edgeX - 5f, h * 0.18f),
                    size = Size(10f, h * 0.64f),
                    cornerRadius = CornerRadius(5f, 5f),
                )
                val travel = (progress / 0.55f).coerceIn(0f, 1f)
                val startX = edgeX - 5f
                val endX = w * 0.52f
                val y = h * 0.5f
                val headX = startX + (endX - startX) * travel
                drawLine(
                    color = accent.copy(alpha = 0.35f * (1f - travel * 0.3f)),
                    start = Offset(startX, y),
                    end = Offset(headX, y),
                    strokeWidth = 3f,
                )
                if (travel > 0.05f) {
                    drawCircle(color = accent, radius = 12f, center = Offset(headX, y))
                    drawCircle(color = surface, radius = 4.5f, center = Offset(headX, y))
                }
            }

            // 沿边下滑＝应用索引：手指沿边向下，索引列表跟着出现
            PracticeIllustrationKind.EDGE_INDEX_DOWN -> {
                val edgeX = w - 10f
                drawRoundRect(
                    color = accent.copy(alpha = 0.55f),
                    topLeft = Offset(edgeX - 5f, h * 0.18f),
                    size = Size(10f, h * 0.64f),
                    cornerRadius = CornerRadius(5f, 5f),
                )
                val travel = (progress / 0.6f).coerceIn(0f, 1f)
                val startX = edgeX - 5f
                val startY = h * 0.24f
                val headY = startY + (h * 0.58f) * travel
                drawLine(
                    color = accent.copy(alpha = 0.35f * (1f - travel * 0.3f)),
                    start = Offset(startX, startY),
                    end = Offset(startX, headY),
                    strokeWidth = 3f,
                )
                if (travel > 0.05f) {
                    drawCircle(color = accent, radius = 12f, center = Offset(startX, headY))
                    drawCircle(color = surface, radius = 4.5f, center = Offset(startX, headY))
                }
                if (progress > 0.5f) {
                    val alpha = ((progress - 0.5f) / 0.5f).coerceIn(0f, 1f)
                    for (index in 0..3) {
                        drawRoundRect(
                            color = textLineColor.copy(alpha = alpha),
                            topLeft = Offset(w * 0.16f, h * (0.20f + index * 0.18f)),
                            size = Size(w * 0.42f, 9f),
                            cornerRadius = CornerRadius(4.5f, 4.5f),
                        )
                    }
                }
            }

            PracticeIllustrationKind.BALL_PICK -> {                val lines = listOf(0.22f, 0.40f, 0.58f)
                lines.forEachIndexed { index, fraction ->
                    val lineWidth = w * if (index == 1) 0.40f else 0.52f
                    drawRoundRect(
                        color = textLineColor,
                        topLeft = Offset(w * 0.14f, h * fraction),
                        size = Size(lineWidth, 9f),
                        cornerRadius = CornerRadius(4.5f, 4.5f),
                    )
                }
                val hitLineY = h * 0.40f
                // 0~0.65 把球拖到文字上，之后显示取词结果
                val travel = (progress / 0.65f).coerceIn(0f, 1f)
                val ballStart = Offset(w * 0.86f, h * 0.86f)
                val ballEnd = Offset(w * 0.22f, hitLineY + 4f)
                val ball = Offset(
                    x = ballStart.x + (ballEnd.x - ballStart.x) * travel,
                    y = ballStart.y + (ballEnd.y - ballStart.y) * travel,
                )
                if (travel >= 0.99f) {
                    drawRoundRect(
                        color = accent.copy(alpha = 0.28f),
                        topLeft = Offset(w * 0.14f, hitLineY - 4f),
                        size = Size(w * 0.40f, 17f),
                        cornerRadius = CornerRadius(5f, 5f),
                    )
                }
                drawCircle(color = accent, radius = 14f, center = ball)
                drawCircle(color = surface, radius = 5.5f, center = ball)
                if (progress > 0.72f) {
                    val alpha = ((progress - 0.72f) / 0.28f).coerceIn(0f, 1f)
                    drawRoundRect(
                        color = accent.copy(alpha = 0.20f * alpha),
                        topLeft = Offset(w * 0.30f, h * 0.62f),
                        size = Size(w * 0.34f, h * 0.20f),
                        cornerRadius = CornerRadius(10f, 10f),
                    )
                    drawRoundRect(
                        color = accent.copy(alpha = 0.55f * alpha),
                        topLeft = Offset(w * 0.30f, h * 0.62f),
                        size = Size(w * 0.34f, h * 0.20f),
                        cornerRadius = CornerRadius(10f, 10f),
                        style = Stroke(width = 2f),
                    )
                }
            }
        }
    }
}
