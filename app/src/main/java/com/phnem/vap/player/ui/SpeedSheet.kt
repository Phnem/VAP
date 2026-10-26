package com.phnem.vap.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phnem.vap.R
import com.phnem.vap.ui.components.PanelColors
import com.phnem.vap.ui.components.PanelHeader
import com.phnem.vap.ui.components.PanelPill
import com.phnem.vap.ui.components.PanelPillRow
import com.phnem.vap.ui.components.VapSheet
import com.phnem.vap.ui.theme.SnProFamily
import com.phnem.vap.util.Haptic
import com.phnem.vap.util.performHaptic
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MIN_SPEED_HUNDREDTHS = 50
private const val SPEED_STEP_HUNDREDTHS = 5
private const val SPEED_STEPS = 51 // 0,5× … 3,0×

private val SPEED_PRESETS = listOf(0.8f, 1f, 1.25f, 1.5f, 2f)

private fun speedAt(step: Int): Float = (MIN_SPEED_HUNDREDTHS + step * SPEED_STEP_HUNDREDTHS) / 100f

private fun stepOf(speed: Float): Int =
    ((speed * 100f - MIN_SPEED_HUNDREDTHS) / SPEED_STEP_HUNDREDTHS).roundToInt().coerceIn(0, SPEED_STEPS - 1)

internal fun formatSpeed(speed: Float): String =
    String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "×"

/**
 * Шторка скорости: крупное значение, линейка делений 0,5×–3,0× с шагом 0,05× и пресеты-таблетки.
 * Тот же элемент, что у скорости аудиокниг Vetro (`SpeedSheetContent`). Скорость применяется сразу,
 * пока листаешь линейку.
 */
@Composable
fun SpeedSheet(speed: Float, onSelect: (Float) -> Unit, onDismiss: () -> Unit) {
    VapSheet(onDismiss) { close ->
        PanelHeader(stringResource(R.string.player_speed), onClose = close)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                formatSpeed(speed),
                color = PanelColors.Accent,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 40.sp,
            )
            Spacer(Modifier.height(6.dp))
            TickRuler(
                count = SPEED_STEPS,
                selected = stepOf(speed),
                majorEvery = 10,
                onSelected = { onSelect(speedAt(it)) },
            )
            Spacer(Modifier.height(14.dp))
            PanelPillRow {
                SPEED_PRESETS.forEach { preset ->
                    PanelPill(formatSpeed(preset), abs(speed - preset) < 0.01f, { onSelect(preset) })
                }
            }
        }
    }
}

/**
 * Горизонтальная линейка из делений: палец листает, деление под центральной риской — выбранное.
 * Каждое пересечение деления — щелчок хаптика. Длинные деления — каждые [majorEvery]. Если значение
 * поменялось снаружи (пресет), линейка сама подъезжает к нему.
 */
@Composable
private fun TickRuler(
    count: Int,
    selected: Int,
    majorEvery: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 14.dp,
) {
    val view = LocalView.current
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceIn(0, count - 1))
    val fling = rememberSnapFlingBehavior(lazyListState = state, snapPosition = SnapPosition.Center)
    val spacingPx = with(LocalDensity.current) { spacing.toPx() }
    val centered by remember {
        derivedStateOf {
            ((state.firstVisibleItemIndex * spacingPx + state.firstVisibleItemScrollOffset) / spacingPx)
                .roundToInt().coerceIn(0, count - 1)
        }
    }
    // Пока линейку двигает сам пресет, промежуточные деления не выбираются: иначе скорость «пробегала» бы
    // по ним и застревала на одном из них.
    var moving by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { centered }.distinctUntilChanged().drop(1).collect {
            if (moving) return@collect
            performHaptic(view, Haptic.Tick)
            onSelected(it)
        }
    }
    LaunchedEffect(selected) {
        if (!state.isScrollInProgress && centered != selected) {
            moving = true
            try {
                state.animateScrollToItem(selected.coerceIn(0, count - 1))
            } finally {
                moving = false
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth().height(64.dp)) {
        val side = maxWidth / 2 - spacing / 2
        LazyRow(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(horizontal = side),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                // Края растворяются: линейка «уходит» за границы, а не обрывается.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.Transparent, 0.22f to Color.Black, 0.78f to Color.Black, 1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            items((0 until count).toList()) { i ->
                Box(Modifier.width(spacing).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    val major = i % majorEvery == 0
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(if (major) 28.dp else 16.dp)
                            .clip(CircleShape)
                            .background(PanelColors.Ink.copy(alpha = if (major) 0.62f else 0.32f)),
                    )
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .width(3.dp)
                .height(40.dp)
                .clip(CircleShape)
                .background(PanelColors.Ink),
        )
    }
}
