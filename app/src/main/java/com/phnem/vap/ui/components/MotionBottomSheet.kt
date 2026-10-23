package com.phnem.vap.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phnem.vap.ui.theme.MotionTokens
import com.phnem.vap.ui.theme.OverlayThemeTokens
import kotlinx.coroutines.flow.first

/**
 * Нижняя шторка со скримом из Vetro Collection (`ui/shared/components/MotionBottomSheet.kt`):
 * пружина крупной поверхности на входе, критически затухающая на выходе, содержимое проявляется,
 * когда оболочка почти приехала. Жизненный цикл отдаётся через [visibleState]; вызывающий снимает
 * шторку, когда `currentState` и `targetState` оба `false` (см. [VapSheet]).
 */
@Composable
fun MotionBottomSheet(
    visibleState: MutableTransitionState<Boolean>,
    onDismiss: () -> Unit,
    isDark: Boolean,
    containerColor: Color,
    modifier: Modifier = Modifier,
    panelModifier: Modifier = Modifier,
    swipe: IosSheetSwipe? = null,
    scrimAlpha: Float = OverlayThemeTokens.scrimAlpha(isDark),
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    val transition = rememberTransition(visibleState, label = "motionSheet")
    val shell = transition.animateFloat(
        transitionSpec = {
            when {
                reducedMotion -> snap()
                targetState -> MotionTokens.largeSurfaceEnter()
                else -> MotionTokens.largeSurfaceExit()
            }
        },
        label = "motionSheetShell",
    ) { shown -> if (shown) 1f else 0f }
    val scrim = transition.animateFloat(
        transitionSpec = {
            if (targetState) tween(MotionTokens.ScrimFadeMillis, easing = MotionTokens.EaseEnter)
            else tween(MotionTokens.ScrimExitMillis, easing = MotionTokens.EaseExit)
        },
        label = "motionSheetScrim",
    ) { shown -> if (shown) 1f else 0f }

    val contentAlpha = remember { Animatable(0f) }
    LaunchedEffect(visibleState.targetState) {
        if (visibleState.targetState) {
            snapshotFlow { shell.value }.first { it >= CONTENT_REVEAL_PROGRESS }
            contentAlpha.animateTo(1f, tween(MotionTokens.EaseEnterMillis, easing = MotionTokens.EaseEnter))
        } else {
            contentAlpha.animateTo(0f, tween(MotionTokens.EaseExitMillis, easing = MotionTokens.EaseExit))
        }
    }

    var panelHeight by remember { mutableFloatStateOf(0f) }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrim.value }
                .background(Color.Black.copy(alpha = scrimAlpha * SheetStyle.scrimFactor))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )

        val shape = sheetPanelShape()
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .sheetFloatingMargin()
                .imePadding()
                .then(panelModifier)
                .onSizeChanged { panelHeight = it.height.toFloat() }
                .then(swipe?.panelModifier ?: Modifier)
                .graphicsLayer {
                    if (reducedMotion) {
                        alpha = contentAlpha.value
                        return@graphicsLayer
                    }
                    alpha = if (panelHeight > 0f) 1f else 0f
                    val travel = panelHeight + SHADOW_ALLOWANCE.toPx() + SheetStyle.FloatExtraTravel.toPx()
                    translationY = (1f - shell.value) * travel
                }
                .iosSheetContainer(shape, isDark, containerColor),
        ) {
            Column(modifier = Modifier.graphicsLayer { alpha = contentAlpha.value }) {
                NoOverscroll { content() }
            }
        }
    }
}

/**
 * Шторка VAP: сама ведёт показ и уход, закрывается тапом по скриму, кнопкой «назад» и свайпом вниз.
 * [onDismiss] вызывается после того, как шторка уехала. Плеер всегда тёмный, поэтому и шторки тёмные
 * независимо от темы приложения (как `PlayerSheetFrame` аудиоплеера Vetro).
 */
@Composable
fun VapSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = true,
    containerColor: Color? = null,
    grabber: Boolean = true,
    /** Шторки плеера скрывают системные панели, как и сам плеер; шторки библиотеки живут в обычном окне. */
    immersive: Boolean = true,
    /** Вызывается сразу при запросе закрытия, до конца анимации ухода (чтобы решение переживало пересоздание экрана). */
    onClosing: () -> Unit = {},
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val latestClosing by rememberUpdatedState(onClosing)
    val close: () -> Unit = { if (visible.targetState) latestClosing(); visible.targetState = false }
    LaunchedEffect(visible.currentState, visible.targetState) {
        if (!visible.currentState && !visible.targetState) latestDismiss()
    }
    val swipe = rememberIosSheetSwipe(close)
    // Отдельное окно: шторка всегда поверх любых слоёв экрана (док, плавающие панели), как и диалоги Collection.
    // «Назад» обрабатывает окно; вложенный BackHandler содержимого имеет приоритет над ним.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = close,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnClickOutside = false),
    ) {
        val window = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        androidx.compose.runtime.SideEffect {
            window?.setDimAmount(0f)
            window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val windowView = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            if (immersive && window != null) androidx.core.view.WindowCompat.getInsetsController(window, windowView).apply {
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalPanelDark provides isDark) {
            BoxWithConstraints(modifier.fillMaxSize()) {
                // Панель не выше экрана за вычетом полей: длинное содержимое прокручивается внутри неё.
                val statusTop = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val limit = (maxHeight - statusTop - SheetStyle.FloatBottom - 24.dp).coerceAtLeast(240.dp)
                MotionBottomSheet(
                    visibleState = visible,
                    onDismiss = close,
                    isDark = isDark,
                    containerColor = containerColor ?: PanelColors.Sheet,
                    panelModifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = limit),
                    swipe = swipe,
                ) {
                    if (grabber) GrabberHandle(isDark, swipe.handleModifier)
                    content(close)
                }
            }
        }
    }
}

private const val CONTENT_REVEAL_PROGRESS = 0.6f
private val SHADOW_ALLOWANCE = 24.dp
