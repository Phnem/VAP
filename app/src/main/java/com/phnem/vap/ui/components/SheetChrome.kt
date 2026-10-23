package com.phnem.vap.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.phnem.vap.ui.theme.MotionTokens
import com.phnem.vap.ui.theme.SquircleShape
import kotlinx.coroutines.launch

/*
 * Каркас парящей шторки Vetro Collection (ui/shared/theme/SheetStyle.kt, SheetGlass.kt и
 * ui/shared/components/IosBottomSheet.kt). Скопировано без изменения поведения; экстера-режим
 * «назад» и счётчик SheetGate отсутствуют: в VAP нет страниц под шторкой, которыми можно
 * управлять свайпом.
 */

/**
 * Вид всех шторок: затемнение слабее на 25%, панель чуть светлее базы с едва заметным градиентом,
 * по периметру тонкая светлая линия, тень мягкая и широкая, панель «парит» с отступами.
 */
object SheetStyle {
    const val scrimFactor: Float = 0.75f
    val FloatInset: Dp = 10.dp
    val FloatBottom: Dp = 8.dp
    val FloatCorner: Dp = 34.dp
    val FloatExtraTravel: Dp = 120.dp
}

fun Modifier.sheetFloatingMargin(): Modifier =
    this.navigationBarsPadding().padding(start = SheetStyle.FloatInset, end = SheetStyle.FloatInset, bottom = SheetStyle.FloatBottom)

/** RoundedCornerShape, а не squircle: путь squircle на панели во весь экран растеризуется на CPU каждый кадр. */
fun sheetPanelShape(): Shape = RoundedCornerShape(SheetStyle.FloatCorner)

fun Modifier.iosSheetContainer(shape: Shape, isDark: Boolean, base: Color): Modifier = this
    .iosSheetTopShadow(isDark)
    .clip(shape)
    .drawBehind { drawRect(sheetBase(base)) }
    .iosSheetGlass(shape, base)

private fun sheetBase(base: Color): Color = lerp(base, Color.White, 0.045f)

/** Мягкая тень парящей панели — нарисованный градиент по кромке, а не нативная тень (нативная роняла fps). */
fun Modifier.iosSheetTopShadow(isDark: Boolean): Modifier = this.drawBehind { drawFloatingGlow(isDark) }

private fun DrawScope.drawFloatingGlow(isDark: Boolean) {
    val w = size.width
    val h = size.height
    val r = SheetStyle.FloatCorner.toPx()
    val up = 54.dp.toPx()
    val side = 34.dp.toPx()
    val down = 30.dp.toPx()
    val alpha = if (isDark) 0.34f else 0.16f
    fun fade() = listOf(
        Color.Black.copy(alpha = alpha),
        Color.Black.copy(alpha = alpha * 0.45f),
        Color.Black.copy(alpha = alpha * 0.12f),
        Color.Transparent,
    )
    drawRect(Brush.verticalGradient(fade().reversed(), startY = -up, endY = 0f), Offset(r, -up), Size(w - 2 * r, up))
    drawRect(Brush.verticalGradient(fade(), startY = h, endY = h + down), Offset(r, h), Size(w - 2 * r, down))
    drawRect(Brush.horizontalGradient(fade().reversed(), startX = -side, endX = 0f), Offset(-side, r), Size(side, h - 2 * r))
    drawRect(Brush.horizontalGradient(fade(), startX = w, endX = w + side), Offset(w, r), Size(side, h - 2 * r))

    fun corner(cx: Float, cy: Float, extent: Float, left: Float, top: Float) {
        val radius = r + extent
        val inner = r / radius
        val brush = Brush.radialGradient(
            0f to Color.Black.copy(alpha = alpha),
            inner to Color.Black.copy(alpha = alpha),
            inner + (1f - inner) * 0.35f to Color.Black.copy(alpha = alpha * 0.45f),
            inner + (1f - inner) * 0.7f to Color.Black.copy(alpha = alpha * 0.12f),
            1f to Color.Transparent,
            center = Offset(cx, cy),
            radius = radius,
        )
        drawRect(brush, Offset(left, top), Size(r + extent, r + extent))
    }
    corner(r, r, up, -up, -up)
    corner(w - r, r, up, w - r, -up)
    corner(r, h - r, down, -down, h - r)
    corner(w - r, h - r, down, w - r, h - r)
}

fun Modifier.iosSheetGlass(shape: Shape, base: Color): Modifier = this.drawWithCache {
    val top = sheetBase(base)
    val gradient = Brush.verticalGradient(0f to lerp(top, Color.White, 0.05f), 1f to top)
    val outline = shape.createOutline(size, layoutDirection, this)
    val rimPath = (outline as? Outline.Generic)?.path ?: Path().apply { addOutline(outline) }
    val rim = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.22f),
        0.5f to Color.White.copy(alpha = 0.18f),
        1f to Color.White.copy(alpha = 0.15f),
    )
    onDrawBehind {
        drawRect(gradient)
        drawPath(rimPath, brush = rim, style = Stroke(width = 0.8.dp.toPx()))
    }
}

/** Swipe-down-to-dismiss: [panelModifier] — на панель, [handleModifier] — на ручку. */
class IosSheetSwipe(val panelModifier: Modifier, val handleModifier: Modifier)

/** Дисмисс по смещению >130dp или скорости >900dp/с, иначе пружинит обратно. */
@Composable
fun rememberIosSheetSwipe(onDismiss: () -> Unit): IosSheetSwipe {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val density = LocalDensity.current
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val dragState = rememberDraggableState { delta ->
        scope.launch { offset.snapTo((offset.value + delta).coerceAtLeast(0f)) }
    }
    val thresholdPx = with(density) { 130.dp.toPx() }
    fun release(velocityPx: Float) {
        if (offset.value > thresholdPx || velocityPx / density.density > 900f) currentOnDismiss()
        else scope.launch { offset.animateTo(0f, MotionTokens.sheetPresent()) }
    }
    val pull = remember {
        SheetPullConnection(
            offset = { offset.value },
            setOffset = { v -> scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { offset.snapTo(v) } },
            onRelease = ::release,
        )
    }
    val panel = Modifier.graphicsLayer { translationY = offset.value }.nestedScroll(pull)
    val handle = Modifier.draggable(state = dragState, orientation = Orientation.Vertical, onDragStopped = { release(it) })
    return IosSheetSwipe(panel, handle)
}

/** Передаёт шторке остаток жеста, который прокручиваемое содержимое не смогло взять. */
internal class SheetPullConnection(
    private val offset: () -> Float,
    private val setOffset: (Float) -> Unit,
    private val onRelease: (velocityY: Float) -> Unit,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val current = offset()
        if (source != NestedScrollSource.UserInput || current <= 0f || available.y >= 0f) return Offset.Zero
        val next = (current + available.y).coerceAtLeast(0f)
        setOffset(next)
        return Offset(0f, next - current)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
        setOffset(offset() + available.y)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (offset() <= 0f) return Velocity.Zero
        onRelease(available.y)
        return Velocity(0f, available.y)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (offset() <= 0f && available.y > 0f) onRelease(available.y)
        return Velocity.Zero
    }
}

/** Без «резинки» на краю прокрутки: остаток жеста должен дойти до самой шторки. */
@Composable
fun NoOverscroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverscrollFactory provides null) { content() }
}

val GrabberReservedTop: Dp = 22.dp

/** Прокручиваемое содержимое заезжает под ручку; первым элементом нужен `Spacer(GrabberReservedTop)`. */
fun Modifier.extendUnderGrabber(): Modifier = layout { measurable, constraints ->
    val extra = GrabberReservedTop.roundToPx()
    val maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + extra else constraints.maxHeight
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = maxHeight))
    layout(placeable.width, (placeable.height - extra).coerceAtLeast(0)) { placeable.place(0, -extra) }
}

/** Системный grab-индикатор iOS: 36×5. */
@Composable
fun GrabberHandle(isDark: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.zIndex(1f).fillMaxWidth().height(GrabberReservedTop),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(width = 36.dp, height = 5.dp)
                .clip(SquircleShape(2.5.dp, smoothing = 0.3f))
                .background((if (isDark) Color.White else Color.Black).copy(alpha = 0.28f)),
        )
    }
}
