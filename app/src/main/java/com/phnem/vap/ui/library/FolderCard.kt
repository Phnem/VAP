package com.phnem.vap.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phnem.vap.R
import com.phnem.vap.library.FolderKind
import com.phnem.vap.library.ThumbTier
import com.phnem.vap.library.ThumbnailCache
import com.phnem.vap.library.VideoFile
import com.phnem.vap.library.VideoFolder
import com.phnem.vap.ui.brand.VapWordmark
import com.phnem.vap.ui.theme.SnProFamily
import com.phnem.vap.ui.theme.isAppInDarkTheme

private val StickerWhite = Color(0xFFFFFFFF)

/** Включено в настройках: папки цветные. По умолчанию выключено, папки матово-чёрные. */
val LocalColoredFolders = staticCompositionLocalOf { false }

/** Соотношение сторон карточки папки (ширина к высоте). */
private const val CARD_ASPECT = 1.04f

/** Ширина ячейки сетки с [columns] колонками: поля [inset] по краям и промежутки [gap] между колонками. */
internal fun gridCellWidth(totalWidth: Dp, inset: Dp, gap: Dp, columns: Int): Dp =
    (totalWidth - inset * 2 - gap * (columns - 1)) / columns

/** Доля высоты папки, которую занимает передняя пластина, и задняя пластина (язычок торчит над ней). */
private const val PLATE_HEIGHT = 0.66f
private const val BACK_HEIGHT = 0.86f

/**
 * Карточка папки: плоская «открывающаяся» папка (задняя пластина, до трёх листов с кадрами видео,
 * передняя пластина с вырезом под язычок), под ней — название и число видео. Рисунок папки — [FolderArt].
 *
 * Раньше передняя пластина была матовым стеклом с размытой подложкой; на телефоне это съедало кадры
 * прокрутки сетки, поэтому пластина теперь просто залитый путь без размытия и без теней.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderCard(
    folder: VideoFolder,
    sheets: List<VideoFile>,
    morph: FolderMorph,
    favorite: Boolean,
    cardWidth: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    targetSlot: Int? = null,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    Column(
        modifier = modifier
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { contentDescription = folder.name },
    ) {
        // Ширину ячейки знает сетка, поэтому подкомпозиция `BoxWithConstraints` на каждую карточку
        // (она идёт в фазе измерения, при каждом появлении ячейки на экране) не нужна.
        Box(Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT)) {
            FolderArt(folder, sheets, morph, cardWidth, cardWidth / CARD_ASPECT, favorite, mini = false, targetSlot = targetSlot)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = folder.name,
                color = ink,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            CountCapsule(folder.totalCount)
        }
    }
}

/**
 * Плоская папка размером [w] × [h]. [mini] — уменьшенный вариант для строки списка (без знака VAP,
 * стикера и звезды). Листы регистрируют свои координаты в [morph]: от них стартует и к ним возвращается
 * полёт. [targetSlot] — сама эта папка цель полёта (вложенная папка в режиме дерева): сюда садится лист
 * с этим индексом, и садится на верхний лист этой папки.
 */
@Composable
internal fun FolderArt(
    folder: VideoFolder,
    sheets: List<VideoFile>,
    morph: FolderMorph,
    w: Dp,
    h: Dp,
    favorite: Boolean,
    mini: Boolean,
    targetSlot: Int?,
    modifier: Modifier = Modifier,
) {
    val dark = isAppInDarkTheme()
    val ink = MaterialTheme.colorScheme.onBackground
    val context = LocalContext.current
    val shown = sheets.take(MAX_SHEETS)

    // Кадры листов грузятся здесь, а не в самом листе. Лист карточки — самый мелкий из трёх готовых
    // размеров (320 px на лист около 265 px).
    val frames = remember(shown.map { it.thumbKey.id }) {
        mutableStateListOf<Bitmap?>().apply { shown.forEach { add(ThumbnailCache.peek(it, ThumbTier.CARD)) } }
    }
    shown.forEachIndexed { i, file ->
        LaunchedEffect(frames, file.thumbKey.id) { frames[i] = ThumbnailCache.frame(context, file, ThumbTier.CARD) }
    }
    val colored = LocalColoredFolders.current
    val colors = remember(folder.kind, folder.id, dark, colored) {
        if (colored) FolderPalette.of(folder, dark) else FolderPalette.matte(dark)
    }
    // Радиусы прежней карточки (24 dp и 14 dp при ширине 160 dp) масштабируются вместе с папкой.
    val corner = w * 0.15f
    val tabCorner = w * 0.0875f

    Box(modifier.size(w, h)) {
        // Задняя пластина и язычок.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(h * BACK_HEIGHT)
                .platePaint(colors.back, colors.rim, RoundedCornerShape(corner)),
        )
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(x = w * 0.06f, y = h * 0.09f)
                .width(w * 0.38f)
                .height(h * 0.12f)
                .platePaint(colors.back, null, RoundedCornerShape(topStart = tabCorner, topEnd = tabCorner)),
        )
        // Порядок рисования: левый, правый, затем средний сверху.
        for (i in intArrayOf(1, 2, 0)) {
            if (shown.getOrNull(i) == null) continue
            Paper(i, w, h, morph, folder.id, frames.getOrNull(i), targetSlot, mini)
        }
        // Передняя пластина: при открытии папки откидывается и гаснет, листы остаются под ней.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(h * PLATE_HEIGHT)
                .graphicsLayer {
                    val p = morph.plateProgress(folder.id)
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    cameraDistance = 14f * density
                    rotationX = -PLATE_OPEN_DEGREES * p
                    scaleY = 1f - PLATE_OPEN_SQUASH * p
                    alpha = 1f - PLATE_OPEN_FADE * p
                }
                .platePaint(colors.front, colors.rim, RoundedCornerShape(corner), colors.frontEnd),
        )
        if (!mini) {
            // Подпись автора слева внизу — знак VAP (решение владельца №3).
            VapWordmark(
                color = colors.mark,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 14.dp)
                    .height(11.dp),
            )
            Sticker(
                folder = folder,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 10.dp)
                    .size(w * 0.30f),
            )
            if (favorite) {
                Icon(
                    painter = painterResource(R.drawable.ic_vap_star),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp),
                )
            }
        }
    }
}

/** Цвета одной папки: передняя пластина (градиент [front] → [frontEnd]), задняя, кант и знак VAP. */
internal class FolderColors(val front: Color, val frontEnd: Color, val back: Color, val rim: Color, val mark: Color)

/**
 * Цвет папки. Палитра подобрана вручную: тёплые оранжевые и красные, горчичный и золотой, глубокие
 * бордовый, сливовый, тёмно-синий, морской и лесной тона, кремовый и почти чёрный. Ни розового, ни
 * кислотного. Известные виды (загрузки, камера, фильмы, скриншоты, документы — те, что помечены
 * стикером) закреплены за своим цветом; остальным цвет выбирается из палитры по идентификатору папки,
 * поэтому у папки он свой и не меняется между запусками.
 */
internal object FolderPalette {
    private val Palette = listOf(
        0xFFF44A22, 0xFFFFA649, 0xFFF7B538, 0xFFFFBE0B, 0xFFFF924F, 0xFFD0391B, 0xFFB31919, 0xFFF5735A,
        0xFF780116, 0xFF5B011D, 0xFF251836, 0xFF14213D, 0xFF283845, 0xFF0F3F49, 0xFF0F3D3A, 0xFF00492C,
        0xFF283618, 0xFF324E44, 0xFFF5DABF, 0xFFEFEDE5, 0xFF161616,
    ).map { Color(it) }

    private fun baseOf(folder: VideoFolder): Color = when (folder.kind) {
        FolderKind.DOWNLOAD -> Color(0xFF0F3F49)
        FolderKind.CAMERA -> Color(0xFFFF924F)
        FolderKind.MOVIES -> Color(0xFFB31919)
        FolderKind.SCREENSHOTS -> Color(0xFF00492C)
        FolderKind.DOCUMENTS -> Color(0xFFF7B538)
        FolderKind.GENERIC -> {
            val raw = ((folder.id.hashCode() * 2654435761L) ushr 8).toInt() and 0x7fffffff
            Palette[raw % Palette.size]
        }
    }

    /**
     * Матово-чёрная папка (режим «цветные папки» выключен). Задняя пластина чуть светлее передней, чтобы
     * язычок и край читались и на чёрном фоне тёмной темы; кант тонкий и светлый, как на макете.
     */
    fun matte(dark: Boolean): FolderColors = FolderColors(
        front = Color(0xFF212124),
        frontEnd = Color(0xFF161618),
        back = if (dark) Color(0xFF2C2C30) else Color(0xFF38383D),
        rim = Color.White.copy(alpha = if (dark) 0.10f else 0.08f),
        mark = Color.White.copy(alpha = 0.45f),
    )

    fun of(folder: VideoFolder, dark: Boolean): FolderColors {
        var base = baseOf(folder)
        val light = base.luminance() > 0.45f
        // Очень тёмные тона на чёрном фоне темы сливались бы с ним: чуть приподнимаем.
        if (dark && base.luminance() < 0.05f) base = lerp(base, Color.White, 0.12f)
        return FolderColors(
            front = lerp(base, Color.White, 0.05f),
            frontEnd = lerp(base, Color.Black, 0.12f),
            back = lerp(base, Color.Black, if (dark) 0.40f else 0.24f),
            rim = if (light) Color.Black.copy(alpha = 0.10f) else Color.White.copy(alpha = if (dark) 0.14f else 0.18f),
            mark = if (light) Color.Black.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.55f),
        )
    }
}

/**
 * Заливка пластины: один цвет или градиент по диагонали ([end] != null) и тонкий кант. Без слоёв, без
 * размытия и без теней: пластина стоит не дороже обычного скруглённого прямоугольника.
 */
private fun Modifier.platePaint(color: Color, rim: Color?, shape: Shape, end: Color? = null): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val brush = end?.let { Brush.linearGradient(listOf(color, it), start = Offset.Zero, end = Offset(size.width, size.height)) }
    val stroke = Stroke(1.dp.toPx())
    onDrawBehind {
        if (brush != null) drawOutline(outline, brush) else drawOutline(outline, color)
        if (rim != null) drawOutline(outline, rim, style = stroke)
    }
}

@Composable
fun CountCapsule(count: Int, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onBackground
    val description = stringResource(R.string.video_count_description, count)
    Box(
        modifier
            .clip(CircleShape)
            .background(ink.copy(alpha = 0.08f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = description },
    ) {
        Text(
            text = count.toString(),
            color = ink.copy(alpha = 0.7f),
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
        )
    }
}

/**
 * Лист: кадр видео в белой рамке; пока кадра нет — бумажная заливка. Верхний лист ([targetSlot] != null)
 * заодно цель полёта, когда эту папку открывают как вложенную.
 */
@Composable
private fun BoxScope.Paper(
    index: Int,
    w: Dp,
    h: Dp,
    morph: FolderMorph,
    folderId: String,
    frame: Bitmap?,
    targetSlot: Int?,
    mini: Boolean,
) {
    val dark = isAppInDarkTheme()
    val pose = PaperPoses[index]
    val shape = RoundedCornerShape(if (mini) 4.dp else 9.dp)
    val slot = targetSlot.takeIf { index == 0 }
    // Карточка ушла с экрана (или пересоздана) — её LayoutCoordinates больше не нужны. Без этого реестр
    // морфа держал узлы всех когда-либо показанных карточек.
    // При смене вида «сетка ↔ список» две карточки одной папки живут одновременно, и уходящая не должна
    // стереть координаты пришедшей: убираем запись, только если она ещё наша.
    val mine = remember { arrayOfNulls<LayoutCoordinates>(1) }
    DisposableEffect(morph, folderId, index, slot) {
        if (slot != null) morph.folderTargets.add(slot)
        onDispose {
            if (morph.paperCoords["$folderId#$index"] === mine[0]) morph.paperCoords.remove("$folderId#$index")
            if (slot != null && morph.targetCoords[slot] === mine[0]) {
                morph.folderTargets.remove(slot)
                morph.targetCoords.remove(slot)
            }
        }
    }
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .offset(x = w * pose.dx, y = h * (0.035f + pose.dy))
            .width(w * 0.56f)
            .aspectRatio(16f / 10f)
            // Координаты до поворота: от них морф считает старт полёта.
            .onPlaced {
                mine[0] = it
                morph.paperCoords["$folderId#$index"] = it
                if (slot != null) morph.targetCoords[slot] = it
            }
            .graphicsLayer {
                rotationZ = pose.rotation
                alpha = if (morph.paperHidden(folderId)) 0f else if (slot != null) morph.targetAlpha(slot) else 1f
            }
            .clip(shape)
            .background(paperColor(index, dark))
            .border(if (mini) 0.75.dp else 1.5.dp, StickerWhite.copy(alpha = if (dark) 0.25f else 0.9f), shape),
    ) {
        frame?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Стикер справа внизу (решение владельца №2). Папка устройства — её значок; папка сериала или
 * фильма — миниатюра постера (файл рядом или обложка внутри видео); нет ни того, ни другого —
 * стикера нет.
 */
@Composable
private fun Sticker(folder: VideoFolder, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon = folder.kind.iconRes()
    if (icon != null) {
        Box(
            modifier = modifier
                .graphicsLayer { rotationZ = 6f }
                .shadow(4.dp, RoundedCornerShape(12.dp), clip = false)
                .clip(RoundedCornerShape(12.dp))
                .background(StickerWhite),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = Color(0xFF1A1A1A),
                modifier = Modifier.fillMaxSize(0.58f),
            )
        }
        return
    }
    val poster by produceState<Bitmap?>(null, folder.id) {
        value = folder.posterUri?.let { ThumbnailCache.image(context, it) }
            ?: folder.files.firstOrNull()?.let { ThumbnailCache.embeddedCover(context, it.uri) }
    }
    poster?.let { bitmap ->
        val shape = RoundedCornerShape(12.dp)
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .graphicsLayer { rotationZ = 6f }
                .shadow(4.dp, shape, clip = false)
                .clip(shape)
                .border(3.dp, StickerWhite, shape),
        )
    }
}

private fun FolderKind.iconRes(): Int? = when (this) {
    FolderKind.DOWNLOAD -> R.drawable.ic_vap_download
    FolderKind.CAMERA -> R.drawable.ic_vap_camera
    FolderKind.MOVIES -> R.drawable.ic_vap_movie
    FolderKind.SCREENSHOTS -> R.drawable.ic_vap_image
    FolderKind.DOCUMENTS -> R.drawable.ic_vap_document
    FolderKind.GENERIC -> null
}
