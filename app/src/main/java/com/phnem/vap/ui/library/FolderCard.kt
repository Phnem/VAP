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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.phnem.vap.ui.glass.FrostedMaterials
import com.phnem.vap.ui.theme.SnProFamily
import com.phnem.vap.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val StickerWhite = Color(0xFFFFFFFF)

/** Соотношение сторон карточки папки (ширина к высоте). */
private const val CARD_ASPECT = 1.04f

/** Ширина ячейки сетки с [columns] колонками: поля [inset] по краям и промежутки [gap] между колонками. */
internal fun gridCellWidth(totalWidth: Dp, inset: Dp, gap: Dp, columns: Int): Dp =
    (totalWidth - inset * 2 - gap * (columns - 1)) / columns

/** Доля высоты карточки, которую занимает передняя пластина. */
private const val PLATE_HEIGHT = 0.66f

/**
 * Карточка папки (design-spec §1.1, §3.3): задняя пластина с язычком, до трёх повёрнутых «листов»
 * (кадры первых видео), матовая передняя пластина, через которую цвет листов просвечивает размытым
 * пятном, стикер справа внизу, знак VAP слева внизу, под карточкой — название и число видео.
 *
 * [sheets] — файлы листов в порядке текущей сортировки. При открытии папки [morph] прячет листы
 * этой карточки (вместо них летят копии), наклоняет и гасит переднюю пластину.
 *
 * Размытое «просвечивание» листов под пластиной — готовая картинка ([FolderPlateBlur]), а не живой
 * `layerBackdrop` на каждую карточку. Живой вариант (design-spec §10, риск №1) на устройстве давал
 * 46 мс на кадр при прокрутке сетки против 12 мс теперь (Samsung SM-S936B, 120 Гц, release).
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
) {
    val dark = isAppInDarkTheme()
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
            val w = cardWidth
            val h = cardWidth / CARD_ASPECT
            val plateShape = RoundedCornerShape(24.dp)
            val context = LocalContext.current
            val localDensity = LocalDensity.current
            val density = localDensity.density
            val material = FrostedMaterials.folder()
            val shown = sheets.take(MAX_SHEETS)

            // Кадры листов грузятся здесь, а не в самом листе: по ним же считается подложка пластины.
            // Лист карточки — самый мелкий из трёх готовых размеров (320 px на лист около 265 px).
            val frames = remember(shown.map { it.thumbKey.id }) {
                mutableStateListOf<Bitmap?>().apply { shown.forEach { add(ThumbnailCache.peek(it, ThumbTier.CARD)) } }
            }
            shown.forEachIndexed { i, file ->
                LaunchedEffect(frames, file.thumbKey.id) { frames[i] = ThumbnailCache.frame(context, file, ThumbTier.CARD) }
            }
            val loaded = frames.count { it != null }
            val wPx = with(localDensity) { w.roundToPx() }
            val hPx = with(localDensity) { h.roundToPx() }
            val plateHPx = with(localDensity) { (h * PLATE_HEIGHT).roundToPx() }
            // Размытое «просвечивание» считается один раз, а не на каждом кадре прокрутки: живое
            // размытие на каждую карточку делало сетку неиграбельной (см. FolderPlateBlur).
            val plateKey = FolderPlateBlur.key(folder.id, dark, wPx, hPx, shown.map { it.thumbKey.id }, frames)
            val plateBlur by produceState(FolderPlateBlur.peek(plateKey), plateKey) {
                if (loaded == 0 || wPx <= 0 || hPx <= 0) {
                    value = null
                    return@produceState
                }
                val snapshot = frames.toList()
                value = withContext(Dispatchers.Default) {
                    FolderPlateBlur.render(plateKey, snapshot, wPx, hPx, plateHPx, density, dark, material.blur.value)
                }
            }
            val saturation = remember(material.saturation) {
                ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(material.saturation) })
            }

            // Задняя пластина и листы.
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(h * 0.86f)
                        .clip(plateShape)
                        .background(if (dark) Color(0xFF2B2B2B) else Color(0xFFE4DDC9)),
                )
                // Язычок задней пластины.
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = w * 0.06f, y = h * 0.09f)
                        .width(w * 0.38f)
                        .height(h * 0.12f)
                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                        .background(if (dark) Color(0xFF2B2B2B) else Color(0xFFE4DDC9)),
                )
                // Порядок рисования: левый, правый, затем средний сверху.
                for (i in intArrayOf(1, 2, 0)) {
                    val file = shown.getOrNull(i) ?: continue
                    Paper(i, w, h, morph, folder.id, frames.getOrNull(i))
                }
            }

            // Передняя матовая пластина; при открытии папки наклоняется и гаснет. Размытая подложка
            // — готовая картинка, остальные слои стекла (тинт, кант, блик) — обычные заливки.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(h * PLATE_HEIGHT)
                    .graphicsLayer {
                        val p = morph.plateProgress(folder.id)
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        cameraDistance = 14f * density
                        rotationX = -PLATE_TILT_DEGREES * p
                        alpha = 1f - 0.9f * p
                    }
                    .then(
                        if (dark) {
                            Modifier
                        } else {
                            Modifier.shadow(
                                elevation = 8.dp,
                                shape = plateShape,
                                clip = false,
                                ambientColor = Color.Black.copy(alpha = 0.2f),
                                spotColor = Color.Black.copy(alpha = 0.2f),
                            )
                        },
                    )
                    .clip(plateShape)
                    .background(material.fallbackFill),
            ) {
                plateBlur?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        filterQuality = FilterQuality.High,
                        colorFilter = saturation,
                        modifier = Modifier.matchParentSize(),
                    )
                }
                Box(Modifier.matchParentSize().background(material.tint))
                Box(Modifier.matchParentSize().border(with(localDensity) { 1f.toDp() }, material.rim, plateShape))
                Box(
                    Modifier.matchParentSize().border(
                        0.75.dp,
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.5f else 0.7f), Color.Transparent)),
                        plateShape,
                    ),
                )
            }

            // Подпись автора слева внизу — знак VAP (решение владельца №3).
            VapWordmark(
                color = ink.copy(alpha = 0.38f),
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

/** Лист: кадр видео в белой рамке; пока кадра нет — бумажная заливка, как в референсе. */
@Composable
private fun BoxScope.Paper(
    index: Int,
    w: Dp,
    h: Dp,
    morph: FolderMorph,
    folderId: String,
    frame: Bitmap?,
) {
    val dark = isAppInDarkTheme()
    val pose = PaperPoses[index]
    val shape = RoundedCornerShape(9.dp)
    // Карточка ушла с экрана (или пересоздана) — её LayoutCoordinates больше не нужны. Без этого реестр
    // морфа держал узлы всех когда-либо показанных карточек.
    DisposableEffect(morph, folderId, index) {
        onDispose { morph.paperCoords.remove("$folderId#$index") }
    }
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .offset(x = w * pose.dx, y = h * (0.04f + pose.dy))
            .width(w * 0.56f)
            .aspectRatio(16f / 10f)
            // Координаты до поворота: от них морф считает старт полёта.
            .onPlaced { morph.paperCoords["$folderId#$index"] = it }
            .graphicsLayer {
                rotationZ = pose.rotation
                alpha = if (morph.paperHidden(folderId)) 0f else 1f
            }
            .shadow(3.dp, shape, clip = false)
            .clip(shape)
            .background(paperColor(index, dark))
            .border(1.5.dp, StickerWhite.copy(alpha = if (dark) 0.25f else 0.9f), shape),
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
