package com.phnem.vap.ui.glass

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import com.kyant.backdrop.Backdrop
import kotlin.math.roundToInt

/**
 * Подложка стекла, которую можно заморозить.
 *
 * Зачем. Стекло (`drawBackdrop`) размывает слой подложки на каждом кадре, где этот слой изменился, и
 * на двух стёклах дока это около 20 проходов рендера на кадр (трасса: 21 проход против 1,5 без блюра).
 * Пока папка открывается или закрывается, подложка под доком меняется на каждом кадре, хотя
 * смотреть на размытие под доком в эти 0,5 с никто не успевает.
 *
 * Как. Подложка — два слоя. [freeze] перестаёт писать содержимое в «живой» слой: он остаётся с
 * последним снимком, а размытие над неизменным слоем рендер-поток не пересчитывает. [thaw] отдаёт
 * запись второму слою, а стекло плавно проявляет его поверх замороженного: размытие «переезжает» на
 * новое содержимое без скачка.
 *
 * Библиотечный `layerBackdrop` для этого не годится: он всегда пишет слой на каждый кадр, а его
 * координаты внутренние. Поэтому свой [Backdrop] и свой модификатор записи [freezableBackdrop].
 * Преобразование слоя (`layerBlock`) не поддерживается: наше стекло его не задаёт.
 */
@Stable
class FreezableBackdrop internal constructor(
    private val first: GraphicsLayer,
    private val second: GraphicsLayer,
) : Backdrop {
    private var firstIsLive by mutableStateOf(true)

    /** Заморожена ли запись: пока истина, живой слой хранит снимок на момент заморозки. */
    var frozen by mutableStateOf(false)
        private set

    /** Прозрачность живого слоя при возврате: 0 — виден только замороженный, 1 — только живой. */
    private var fade by mutableFloatStateOf(1f)

    internal var layerCoordinates by mutableStateOf<LayoutCoordinates?>(null)

    internal val recordingLayer: GraphicsLayer get() = if (firstIsLive) first else second
    private val staleLayer: GraphicsLayer get() = if (firstIsLive) second else first

    override val isCoordinatesDependent: Boolean get() = true

    fun freeze() {
        fade = 1f
        frozen = true
    }

    /** Разморозить: запись идёт во второй слой, а замороженный плавно уступает ему место. */
    suspend fun thaw() {
        if (!frozen) return
        firstIsLive = !firstIsLive
        fade = 0f
        frozen = false
        animate(0f, 1f, animationSpec = tween(durationMillis = FADE_MS, easing = FastOutSlowInEasing)) { value, _ -> fade = value }
        fade = 1f
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val layerCoords = layerCoordinates?.takeIf { it.isAttached } ?: return
        // Слой подложки лежит в координатах контейнера, а стекло рисует в своих: сдвигаем на разницу
        // позиций. Размер контекста на время отрисовки — размер слоя, иначе слой обрезался бы по стеклу.
        val offset = if (coordinates != null && coordinates.isAttached) {
            layerCoords.positionInWindow() - coordinates.positionInWindow()
        } else {
            Offset.Zero
        }
        val savedSize = drawContext.size
        drawContext.size = layerCoords.size.toSize()
        try {
            translate(offset.x, offset.y) {
                val f = fade
                if (f < 1f) {
                    staleLayer.alpha = 1f
                    drawLayer(staleLayer)
                }
                val live = recordingLayer
                live.alpha = f
                drawLayer(live)
            }
        } finally {
            drawContext.size = savedSize
        }
    }

    private companion object {
        const val FADE_MS = 150
    }
}

@Composable
fun rememberFreezableBackdrop(): FreezableBackdrop {
    val first = rememberGraphicsLayer()
    val second = rememberGraphicsLayer()
    return remember(first, second) { FreezableBackdrop(first, second) }
}

/**
 * Нарисовать содержимое [outer] в слой, который пишется областью [recording].
 *
 * `outer.drawContent()` рисует на холст контекста самой области [outer], то есть на экран, а не в слой
 * (проверено: слой получался пустым и стекло показывало один фон). Поэтому, как и `recordLayer`
 * библиотеки backdrop, на время вызова подменяем контекст [outer] на контекст записи.
 */
private fun drawContentInto(outer: ContentDrawScope, recording: DrawScope) {
    val target = outer.drawContext
    val source = recording.drawContext
    val density = target.density
    val direction = target.layoutDirection
    val canvas = target.canvas
    val size = target.size
    val layer = target.graphicsLayer
    target.density = source.density
    target.layoutDirection = source.layoutDirection
    target.canvas = source.canvas
    target.size = source.size
    target.graphicsLayer = source.graphicsLayer
    target.canvas.save()
    try {
        outer.drawContent()
    } finally {
        target.canvas.restore()
        target.density = density
        target.layoutDirection = direction
        target.canvas = canvas
        target.size = size
        target.graphicsLayer = layer
    }
}

/**
 * Собрать содержимое в подложку [backdrop]: рисуется как обычно и, пока подложка не заморожена,
 * ещё раз в её живой слой (так же устроен `layerBackdrop` библиотеки).
 *
 * [background] — цвет фона экрана: слой подложки записывается НЕПРОЗРАЧНЫМ, как в Vetro
 * (`rememberLayerBackdrop { drawRect(bgColor); drawContent() }`). Без заливки в слое почти все пиксели
 * прозрачны, размытие даёт полупрозрачный результат, и настоящий резкий текст под доком просвечивает
 * сквозь «стекло»: оно выглядит просто прозрачным.
 */
fun Modifier.freezableBackdrop(backdrop: FreezableBackdrop, background: Color): Modifier =
    this
        .onGloballyPositioned { if (it.isAttached) backdrop.layerCoordinates = it }
        .drawWithContent {
            drawContent()
            if (!backdrop.frozen) {
                backdrop.recordingLayer.record(
                    density = this,
                    layoutDirection = layoutDirection,
                    size = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                ) {
                    drawRect(background)
                    drawContentInto(this@drawWithContent, this)
                }
            }
        }
