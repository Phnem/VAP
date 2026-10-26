package com.phnem.vap.ui.library

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import com.phnem.vap.library.ThumbTier
import com.phnem.vap.library.ThumbnailCache
import com.phnem.vap.library.VideoFile
import com.phnem.vap.library.VideoFolder
import com.phnem.vap.ui.theme.MotionTokens
import com.phnem.vap.ui.theme.isAppInDarkTheme
import kotlin.math.roundToInt

/** Поза «листа» на папке: сдвиг от центра в долях ширины и высоты и поворот. Индекс 0 — средний, сверху. */
internal data class PaperPose(val dx: Float, val dy: Float, val rotation: Float)

internal val PaperPoses = listOf(
    PaperPose(0f, 0f, -2f),
    PaperPose(-0.18f, 0.05f, -9f),
    PaperPose(0.18f, 0.02f, 7f),
)

internal const val MAX_SHEETS = 3

/** Насколько откидывается передняя пластина открытой папки, градусов, и насколько она при этом гаснет. */
internal const val PLATE_OPEN_DEGREES = 52f
internal const val PLATE_OPEN_FADE = 0.12f

/** Насколько откинутая пластина сплющивается по высоте: открытая папка ниже закрытой, листы видны целиком. */
internal const val PLATE_OPEN_SQUASH = 0.34f

internal fun paperColor(index: Int, dark: Boolean): Color =
    if (dark) {
        listOf(Color(0xFF3A3A3A), Color(0xFF4A3F22), Color(0xFF4A2C26))[index % 3]
    } else {
        listOf(Color(0xFFFFFFFF), Color(0xFFFCE7A8), Color(0xFFF7B7A8))[index % 3]
    }

/**
 * Морф «папка открывается»: до трёх листов летят с карточки папки в первые карточки файлов,
 * передняя пластина откидывается и гаснет. Закрытие идёт тем же путём назад, но пластина закрывается
 * только когда листы уже вернулись в папку: иначе листы пролетали бы сквозь неё.
 *
 * Механика взята из `ShelfMorph` полок аудиокниг Vetro:
 * - один общий прогресс `0..1` и две таблицы прямоугольников `from` / `to`;
 * - координаты узлов пишутся без состояния (`onPlaced`) и читаются только в момент перехода,
 *   поэтому скролл главного экрана не превращается в поток записей в снапшот;
 * - перед чтением целей ждём два кадра: композиция и раскладка экрана папки.
 *
 * Прямоугольники берутся в координатах контейнера [root], а не окна, и необрезанными.
 */
@Stable
class FolderMorph {
    var open by mutableStateOf<VideoFolder?>(null)
        private set

    /** Файлы, чьи кадры летят; индекс совпадает с индексом листа на карточке. */
    var sheets by mutableStateOf<List<VideoFile>>(emptyList())
        private set

    val progress = Animatable(0f)

    /** Насколько откинута передняя пластина открытой папки (0 — закрыта). Двигается отдельно от полёта листов. */
    val plate = Animatable(0f)

    /** Номера целей, которые сами являются папками (режим дерева): сюда лист садится и растворяется. */
    internal val folderTargets = HashSet<Int>()

    /**
     * Листы-копии рисуются только пока идёт переход. Раньше слой жил, пока папка открыта, и копии
     * висели на координатах, снятых в момент открытия, поверх прокручиваемого списка.
     */
    var flying by mutableStateOf(false)
        private set

    internal val paperCoords = HashMap<String, LayoutCoordinates>()
    internal val targetCoords = HashMap<Int, LayoutCoordinates>()
    internal var root: LayoutCoordinates? = null

    var from by mutableStateOf<List<Rect?>>(emptyList())
        private set
    var to by mutableStateOf<List<Rect?>>(emptyList())
        private set

    /** Лист на самой карточке прячется, пока вместо него летит копия. */
    fun paperHidden(folderId: String): Boolean = open?.id == folderId && progress.value > 0.001f

    /** Настоящая карточка файла прячется до конца полёта. */
    fun targetHidden(index: Int): Boolean =
        open != null && progress.value < 0.999f && from.getOrNull(index) != null && to.getOrNull(index) != null

    /** Прозрачность настоящей цели-папки: она проявляется под садящимся листом. */
    fun targetAlpha(index: Int): Float =
        if (!targetHidden(index)) 1f else ((progress.value - 0.8f) / 0.2f).coerceIn(0f, 1f)

    /** Откидывание пластины именно этой папки. */
    fun plateProgress(folderId: String): Float = if (open?.id == folderId) plate.value else 0f

    suspend fun expand(folder: VideoFolder, sheets: List<VideoFile>, reducedMotion: Boolean) {
        if (open != null) return
        this.sheets = sheets
        from = sheets.indices.map { rectOf(paperCoords["${folder.id}#$it"]) }
        targetCoords.clear()
        to = emptyList()
        open = folder
        withFrameNanos { }
        withFrameNanos { }
        to = sheets.indices.map { rectOf(targetCoords[it]) }
        if (reducedMotion) {
            plate.snapTo(1f)
            progress.snapTo(1f)
        } else {
            flying = true
            try {
                // Пластина откидывается сразу, листы вылетают из-под неё.
                coroutineScope {
                    launch { plate.animateTo(1f, MotionTokens.springSurface()) }
                    progress.animateTo(1f, MotionTokens.largeSurfaceEnter())
                }
            } finally {
                flying = false
            }
        }
    }

    /** Открыть без анимации: возврат на экран после пересоздания Activity. */
    suspend fun restore(folder: VideoFolder) {
        if (open != null) return
        sheets = emptyList()
        from = emptyList()
        to = emptyList()
        open = folder
        plate.snapTo(1f)
        progress.snapTo(1f)
    }

    /** Сброс без анимации: папка пропала при пересканировании. */
    suspend fun reset() {
        progress.snapTo(0f)
        plate.snapTo(0f)
        flying = false
        open = null
        sheets = emptyList()
        from = emptyList()
        to = emptyList()
    }

    suspend fun collapse(reducedMotion: Boolean) {
        val folder = open ?: return
        // Те же `from` (карточка папки) и `to` (карточки файлов), что и при открытии: прогресс идёт
        // от 1 к 0, поэтому копии летят обратно. Раньше концы были переставлены ещё раз, и листы
        // летели в ту же сторону, что и при открытии.
        from = sheets.indices.map { rectOf(paperCoords["${folder.id}#$it"]) }
        to = sheets.indices.map { rectOf(targetCoords[it]) }
        if (reducedMotion) {
            progress.snapTo(0f)
            plate.snapTo(0f)
        } else {
            flying = true
            try {
                progress.animateTo(0f, MotionTokens.largeSurfaceExit())
            } finally {
                flying = false
            }
            // Листы уже лежат в открытой папке — теперь она закрывается.
            plate.animateTo(0f, MotionTokens.springExit())
        }
        open = null
        sheets = emptyList()
        targetCoords.clear()
    }

    private fun rectOf(coords: LayoutCoordinates?): Rect? {
        val r = root ?: return null
        if (coords == null || !coords.isAttached || !r.isAttached) return null
        val topLeft = r.localPositionOf(coords, Offset.Zero)
        return Rect(topLeft, coords.size.toSize())
    }
}

/** Слой летящих листов поверх главного экрана и экрана папки на всё время перехода. */
@Composable
fun FlyingPapers(morph: FolderMorph, modifier: Modifier = Modifier) {
    if (morph.open == null || !morph.flying) return
    Box(modifier.fillMaxSize()) {
        // Порядок как на карточке: боковые под средним.
        for (i in intArrayOf(1, 2, 0)) {
            val file = morph.sheets.getOrNull(i) ?: continue
            key(file.key) { FlyingPaper(i, file, morph) }
        }
    }
}

@Composable
private fun FlyingPaper(index: Int, file: VideoFile, morph: FolderMorph) {
    val from = morph.from.getOrNull(index) ?: return
    val to = morph.to.getOrNull(index) ?: return
    val context = LocalContext.current
    // Лист летит от размера листа карточки до размера ячейки сетки: на старте годится и мелкий кадр, к концу нужен размер сетки.
    val frame by produceState(
        ThumbnailCache.peek(file, ThumbTier.GRID) ?: ThumbnailCache.peek(file, ThumbTier.CARD),
        file.thumbKey.id,
    ) { value = ThumbnailCache.frame(context, file, ThumbTier.GRID) }
    val pose = PaperPoses[index]
    val paper = paperColor(index, isAppInDarkTheme())
    val image = remember(frame) { frame?.asImageBitmap() }
    val imageBrush = remember(image) { image?.let { ShaderBrush(ImageShader(it, TileMode.Clamp, TileMode.Clamp)) } }

    Box(
        Modifier
            .layout { measurable, _ ->
                val p = morph.progress.value
                val r = lerp(from, to, p)
                val w = r.width.roundToInt().coerceAtLeast(1)
                val h = r.height.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
            }
            .graphicsLayer {
                val p = morph.progress.value
                rotationZ = lerp(pose.rotation, 0f, p)
                // Лист, садящийся в папку-цель, растворяется под её верхним листом.
                if (index in morph.folderTargets) alpha = 1f - ((p - 0.8f) / 0.2f).coerceIn(0f, 1f)
            }
            // Тень рисуется прямоугольниками, а не `shadowElevation`: размер листа меняется на каждом
            // кадре полёта, и Skia каждый раз заново считал маску тени на CPU и грузил её в GPU
            // (на трассе — по одной загрузке текстуры ~430x255 на тень на кадр).
            .drawBehind {
                val p = morph.progress.value
                val fade = 1f - p
                if (fade <= 0.01f) return@drawBehind
                val radius = lerp(9.dp.toPx(), 16.dp.toPx(), p)
                val shift = 1.5.dp.toPx()
                for ((grow, alpha) in FlyingShadowLayers) {
                    val g = grow.toPx()
                    drawRoundRect(
                        color = Color.Black.copy(alpha = alpha * fade),
                        topLeft = Offset(-g, -g + shift),
                        size = Size(size.width + 2 * g, size.height + 2 * g),
                        cornerRadius = CornerRadius(radius + g),
                    )
                }
            },
    ) {
        // Скругление — это закрашенный скруглённый прямоугольник (картинка идёт кистью-шейдером), а не
        // обрезка слоем: у обрезки на каждом кадре полёта менялись размер и радиус, и Skia заново
        // растрил маску на CPU и грузил её в GPU (около 680 загрузок за шесть переходов).
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                val p = morph.progress.value
                val radius = lerp(9.dp.toPx(), 16.dp.toPx(), p)
                drawRoundRect(paper, cornerRadius = CornerRadius(radius))
                if (image != null && imageBrush != null) {
                    // Как ContentScale.Crop: картинка по центру, закрывает весь лист.
                    val s = maxOf(size.width / image.width, size.height / image.height)
                    val dx = (size.width - image.width * s) / 2f
                    val dy = (size.height - image.height * s) / 2f
                    withTransform({
                        translate(dx, dy)
                        scale(s, s, Offset.Zero)
                    }) {
                        drawRoundRect(
                            brush = imageBrush,
                            topLeft = Offset(-dx / s, -dy / s),
                            size = Size(size.width / s, size.height / s),
                            cornerRadius = CornerRadius(radius / s),
                        )
                    }
                }
                // Белая рамка листа гаснет к концу полёта: у карточки файла рамки нет.
                val rim = 1.5.dp.toPx()
                val frameAlpha = 0.9f * (1f - p)
                if (frameAlpha > 0.01f) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = frameAlpha),
                        topLeft = Offset(rim / 2, rim / 2),
                        size = Size(size.width - rim, size.height - rim),
                        cornerRadius = CornerRadius(9.dp.toPx() - rim / 2),
                        style = Stroke(rim),
                    )
                }
            },
        )
    }
}

/** Слои имитации мягкой тени: на сколько шире листа и с какой прозрачностью (при старте полёта). */
private val FlyingShadowLayers = listOf(1.dp to 0.07f, 2.dp to 0.05f, 3.dp to 0.03f)
