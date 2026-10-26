package com.phnem.vap.ui.components.pulltorefresh

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phnem.vap.ui.theme.BrandOrange
import com.phnem.vap.ui.theme.BrandOrangeBright
import com.phnem.vap.util.Haptic
import com.phnem.vap.util.performHaptic
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/*
 * Pull-to-refresh из Vetro Collection (`ui/shared/components/pulltorefresh`): тот же цикл фаз и тот же
 * индикатор, только цвета и haptic — VAP.
 */

/** Фазы pull-to-refresh, отвязанные от «сырого» isRefreshing (см. [rememberPullRefreshController]). */
enum class RefreshPhase { Idle, Pulling, Refreshing, Holding, Releasing }

/**
 * Общий холдер состояния pull-to-refresh. Создаётся на уровне экрана и шарится между контентом
 * (который едет вниз на [revealFraction]) и [PullRefreshIndicator].
 *
 * Все анимируемые поля читаются только в draw/placement-фазе — без рекомпозиции на кадр.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
class PullRefreshController internal constructor(
    val pullState: PullToRefreshState,
    internal val phaseState: MutableState<RefreshPhase>,
    /** Видимость/фиксация: 1 во время загрузки и удержания, уезжает в 0 на Releasing. */
    val appear: Animatable<Float, AnimationVector1D>,
    /** Непрерывный угол вращения спиннера во время загрузки. */
    val spin: Animatable<Float, AnimationVector1D>,
) {
    internal val refreshTick = mutableIntStateOf(0)

    val phase: RefreshPhase get() = phaseState.value

    /**
     * Вызывается из `onRefresh`. Цикл фиксации запускает именно жест, а не флаг isRefreshing: поэтому
     * удержание работает и при мгновенном обновлении, когда флаг не успевает подняться.
     */
    fun notifyRefreshInvoked() {
        refreshTick.intValue++
    }

    /** Доля раскрытия: `max(палец, зафиксированное)`. Ей управляются и сдвиг контента, и индикатор. */
    fun revealFraction(): Float =
        max(pullState.distanceFraction.coerceIn(0f, 1.2f), appear.value)
}

/**
 * Собирает [PullRefreshController]. Цикл: `onRefresh` → Refreshing (контент зафиксирован, спиннер
 * крутится) → держим `max(реальный обход, minVisibleMs)` → Releasing (возврат) → Idle. Повторный
 * рефреш во время удержания перезапускает цикл.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberPullRefreshController(
    pullState: PullToRefreshState,
    isRefreshing: Boolean,
    view: View,
    minVisibleMs: Long = 2000L,
    releaseAnimMs: Long = 380L,
): PullRefreshController {
    val phaseState = remember { mutableStateOf(RefreshPhase.Idle) }
    val appear = remember { Animatable(0f) }
    val spin = remember { Animatable(0f) }
    val controller = remember(pullState, phaseState, appear, spin) {
        PullRefreshController(pullState, phaseState, appear, spin)
    }

    val isRefreshingState = rememberUpdatedState(isRefreshing)

    LaunchedEffect(controller.refreshTick.intValue) {
        if (controller.refreshTick.intValue == 0) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        phaseState.value = RefreshPhase.Refreshing
        // Даём флагу реального обхода шанс подняться, затем ждём его опускания.
        withTimeoutOrNull(150L) { snapshotFlow { isRefreshingState.value }.first { it } }
        snapshotFlow { isRefreshingState.value }.first { !it }
        phaseState.value = RefreshPhase.Holding
        val remaining = (minVisibleMs - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0L)
        delay(remaining)
        phaseState.value = RefreshPhase.Releasing
        delay(releaseAnimMs)
        phaseState.value = RefreshPhase.Idle
    }

    // Жест трогает фазу только в покое (Idle/Pulling).
    LaunchedEffect(pullState) {
        snapshotFlow { pullState.distanceFraction }.collect { f ->
            val p = phaseState.value
            if (p == RefreshPhase.Idle || p == RefreshPhase.Pulling) {
                phaseState.value = if (f > 0.001f) RefreshPhase.Pulling else RefreshPhase.Idle
            }
        }
    }

    val phase = phaseState.value

    LaunchedEffect(phase) {
        when (phase) {
            RefreshPhase.Refreshing, RefreshPhase.Holding ->
                appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 250f))
            RefreshPhase.Releasing ->
                appear.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 170f))
            RefreshPhase.Idle, RefreshPhase.Pulling ->
                if (appear.value != 0f) appear.snapTo(0f)
        }
    }

    // Вращение спиннера — только пока он на экране.
    val spinning = phase == RefreshPhase.Refreshing || phase == RefreshPhase.Holding || phase == RefreshPhase.Releasing
    LaunchedEffect(spinning) {
        spin.snapTo(0f)
        if (!spinning) return@LaunchedEffect
        spin.animateTo(
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(850, easing = LinearEasing)),
        )
    }

    // Haptic один раз при пересечении порога срабатывания.
    LaunchedEffect(pullState) {
        snapshotFlow { pullState.distanceFraction >= 1f }
            .distinctUntilChanged()
            .collect { above -> if (above) performHaptic(view, Haptic.Light) }
    }

    return controller
}

/**
 * Всё «потянуть для обновления» одним блоком: индикатор за содержимым, содержимое сдвигается вниз на
 * [revealMax] при полном пулле. Нужен и главному экрану, и экрану папки.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefreshHost(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    revealMax: Dp = 96.dp,
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        val pullState = rememberPullToRefreshState()
        val controller = rememberPullRefreshController(pullState, isRefreshing, LocalView.current)
        PullRefreshIndicator(controller, revealMax, Modifier.statusBarsPadding())
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, (controller.revealFraction() * revealMax.toPx()).roundToInt()) },
        ) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    controller.notifyRefreshInvoked()
                    onRefresh()
                },
                state = pullState,
                // Свой индикатор рисуется выше, стоковый выключен.
                indicator = {},
                modifier = Modifier.fillMaxSize(),
            ) { content() }
        }
    }
}

/**
 * Индикатор живёт за контентом: уезжающий вниз контент открывает его в зазоре сверху. Во время пулла
 * кольцо заполняется по жесту (с ведущей точкой), при обходе — вращающаяся дуга с градиентным хвостом.
 * Позиция, масштаб и прозрачность — только в draw-фазе.
 *
 * @param revealMax высота зазора при полном пулле, в нём по центру стоит спиннер.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.PullRefreshIndicator(
    controller: PullRefreshController,
    revealMax: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(revealMax)
            .graphicsLayer { alpha = (controller.revealFraction() / 0.28f).coerceIn(0f, 1f) },
    ) {
        val reveal = controller.revealFraction()
        val committed = controller.appear.value
        val progress = controller.pullState.distanceFraction.coerceIn(0f, 1f)

        val strokeW = 3.dp.toPx()
        val ringR = 12.dp.toPx() * (0.65f + 0.35f * max(progress, committed))
        val center = Offset(size.width / 2f, reveal * size.height / 2f)
        val arcTopLeft = Offset(center.x - ringR, center.y - ringR)
        val arcSize = Size(ringR * 2f, ringR * 2f)

        drawCircle(
            color = BrandOrange.copy(alpha = 0.16f),
            radius = ringR,
            center = center,
            style = Stroke(width = strokeW),
        )

        if (committed > 0.02f) {
            rotate(controller.spin.value, pivot = center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0.0f to Color.Transparent,
                        0.55f to BrandOrange.copy(alpha = 0.15f * committed),
                        1.0f to BrandOrangeBright.copy(alpha = committed),
                        center = center,
                    ),
                    startAngle = 15f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round),
                )
            }
        } else {
            val sweep = progress * 300f
            drawArc(
                color = BrandOrange,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokeW, cap = StrokeCap.Round),
            )
            if (progress > 0.02f) {
                val a = (-90f + sweep) * (PI / 180f).toFloat()
                drawCircle(
                    color = BrandOrangeBright,
                    radius = strokeW * 0.9f,
                    center = Offset(center.x + ringR * cos(a), center.y + ringR * sin(a)),
                )
            }
        }
    }
}
