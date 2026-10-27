package com.phnem.vap.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phnem.vap.ui.theme.SnProFamily
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Пружинящая подложка выбора: одна «пилюля» переезжает под выбранный пункт ряда, на лету растягиваясь
 * по скорости (squash & stretch) и пружиня на остановке. Основа — эталон `Spring Pill Toggle`
 * (жёсткость 380, растяжение 0,00065 на пикс/с, не больше +28 %), но «резиновость» уменьшена вдвое:
 * растяжение 0,000325 и не больше +14 %, затухание 0,72 вместо 0,56, чтобы подложка меньше качалась.
 * Работает для двух кнопок и для любого их числа.
 */
private const val SPRING_STIFFNESS = 380f
private const val SPRING_DAMPING_RATIO = 0.72f
private const val STRETCH_PER_PX_PER_SECOND = 0.000325f
private const val STRETCH_MAX = 0.14f
private val PILL_GAP = 8.dp
private val PILL_SIDE_PADDING = 16.dp
private val PILL_SIDE_PADDING_MIN = 6.dp

/**
 * Ряд пунктов-таблеток с пружинящей подложкой. [inactiveFill] — заливка каждого пункта, [activeFill] —
 * подложка выбранного, [activeText] и [inactiveText] — цвет подписей.
 *
 * Ряд собран из трёх слоёв: нижний рисует заливки пунктов, над ним едет подложка, верхний несёт подписи
 * и нажатия. Подложка поэтому лежит над заливкой, но под текстом.
 */
@Composable
fun SpringPillRow(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    inactiveFill: Color,
    activeFill: Color,
    activeText: Color,
    inactiveText: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 15.sp,
    verticalPadding: Dp = 8.dp,
    minHeight: Dp = 0.dp,
) {
    // Для каждого пункта: левый край и ширина в координатах ряда; NaN — ещё не измерен.
    val lefts = remember(labels.size) { mutableStateListOf<Float>().apply { repeat(labels.size) { add(Float.NaN) } } }
    val widths = remember(labels.size) { mutableStateListOf<Float>().apply { repeat(labels.size) { add(Float.NaN) } } }
    val x = remember { Animatable(0f) }
    val w = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }

    val targetX = lefts.getOrNull(selected) ?: Float.NaN
    val targetW = widths.getOrNull(selected) ?: Float.NaN
    LaunchedEffect(selected, targetX, targetW) {
        if (targetX.isNaN() || targetW.isNaN()) return@LaunchedEffect
        if (!placed) {
            x.snapTo(targetX)
            w.snapTo(targetW)
            placed = true
        } else {
            val spec = spring<Float>(dampingRatio = SPRING_DAMPING_RATIO, stiffness = SPRING_STIFFNESS)
            launch { x.animateTo(targetX, spec) }
            launch { w.animateTo(targetW, spec) }
        }
    }

    BoxWithConstraints(modifier) {
        // Если пункты вместе с зазорами не помещаются, сужаем боковые поля: иначе последнему пункту достаётся
        // остаток ширины, и его подпись переносится по буквам («Englis» без «h»).
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val sidePadding = remember(labels, fontSize, constraints.maxWidth, density) {
            if (constraints.maxWidth == Constraints.Infinity) return@remember PILL_SIDE_PADDING
            val style = TextStyle(fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = fontSize)
            val text = labels.sumOf { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }
            val gaps = with(density) { PILL_GAP.toPx() } * (labels.size - 1)
            val room = (constraints.maxWidth - gaps - text) / (2f * labels.size) - 1f
            with(density) { room.toDp() }.coerceIn(PILL_SIDE_PADDING_MIN, PILL_SIDE_PADDING)
        }
        // Слой 1: заливки пунктов (подписи невидимы, нужны только для размера).
        Row(horizontalArrangement = Arrangement.spacedBy(PILL_GAP)) {
            labels.forEachIndexed { index, label ->
                PillCell(
                    label = label,
                    textColor = Color.Transparent,
                    fontSize = fontSize,
                    verticalPadding = verticalPadding,
                    sidePadding = sidePadding,
                    minHeight = minHeight,
                    modifier = Modifier
                        .onPlaced {
                            val left = it.positionInParent().x
                            val width = it.size.width.toFloat()
                            if (lefts[index] != left) lefts[index] = left
                            if (widths[index] != width) widths[index] = width
                        }
                        .clip(CircleShape)
                        .background(inactiveFill),
                )
            }
        }
        // Слой 2: подложка. Положение, ширина и растяжение меняются только в фазах размещения и рисования.
        Box(Modifier.matchParentSize()) {
            if (placed) {
                Box(
                    Modifier
                        .layout { measurable, constraints ->
                            val width = w.value.roundToInt().coerceAtLeast(1)
                            val placeable = measurable.measure(Constraints.fixed(width, constraints.maxHeight))
                            layout(width, constraints.maxHeight) { placeable.place(0, 0) }
                        }
                        .graphicsLayer {
                            translationX = x.value
                            val stretch = (abs(x.velocity) * STRETCH_PER_PX_PER_SECOND).coerceAtMost(STRETCH_MAX)
                            scaleX = 1f + stretch
                            scaleY = 1f / sqrt(scaleX)
                        }
                        .clip(CircleShape)
                        .background(activeFill),
                )
            }
        }
        // Слой 3: подписи и нажатия.
        Row(horizontalArrangement = Arrangement.spacedBy(PILL_GAP)) {
            labels.forEachIndexed { index, label ->
                val color by animateColorAsState(
                    if (index == selected) activeText else inactiveText,
                    tween(180),
                    label = "pillText",
                )
                PillCell(
                    label = label,
                    textColor = color,
                    fontSize = fontSize,
                    verticalPadding = verticalPadding,
                    sidePadding = sidePadding,
                    minHeight = minHeight,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(index) },
                        )
                        .semantics { this.selected = index == selected; role = Role.RadioButton },
                )
            }
        }
    }
}

@Composable
private fun PillCell(
    label: String,
    textColor: Color,
    fontSize: TextUnit,
    verticalPadding: Dp,
    sidePadding: Dp,
    minHeight: Dp,
    modifier: Modifier,
) {
    Box(
        modifier.defaultMinSize(minHeight = minHeight).padding(horizontal = sidePadding, vertical = verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = fontSize, maxLines = 1, softWrap = false)
    }
}
