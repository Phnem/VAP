package com.phnem.vap.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.phnem.vap.R
import com.phnem.vap.library.ThumbTier
import com.phnem.vap.library.ThumbnailCache
import com.phnem.vap.library.VideoFile
import com.phnem.vap.ui.glass.FrostedMaterials
import com.phnem.vap.ui.glass.frostedGlass
import com.phnem.vap.ui.theme.MotionTokens
import com.phnem.vap.ui.theme.SnProFamily
import com.phnem.vap.ui.theme.isAppInDarkTheme
import kotlin.math.roundToInt

private val DeleteRed = Color(0xFFFF453A)
/** Миниатюры в стопке квадратные: кадр 16:9 обрезается по центру. */
private val StackThumbSize = 60.dp
private val StackPoses = floatArrayOf(0f, 3f, -2.5f, 4f)

/** Кружок выбора и затемнение на карточке видео. [SelectVisual.PENDING] — выбрано и уже «улетело» в стопку. */
@Composable
internal fun BoxScope.SelectionOverlay(visual: SelectVisual) {
    if (visual == SelectVisual.NONE) return
    val primary = WatchedGreen
    if (visual == SelectVisual.SELECTED || visual == SelectVisual.PENDING) {
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (visual == SelectVisual.PENDING) 0.55f else 0.2f)))
    }
    if (visual == SelectVisual.SELECTED) {
        Box(Modifier.matchParentSize().border(3.dp, primary, RoundedCornerShape(16.dp)))
    }
    if (visual != SelectVisual.PENDING) {
        val selected = visual == SelectVisual.SELECTED
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (selected) primary else Color.Black.copy(alpha = 0.25f))
                .then(if (selected) Modifier else Modifier.border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(painterResource(R.drawable.ic_vap_check), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * Стеклянный мини-док сверху справа. При выборе — капсула с действиями (корзина, перенос, избранное,
 * просмотрено и, если выбрано одно видео, «ещё»); пока выбранные ждут папку — два пузырька: принять и отменить.
 */
@Composable
fun BoxScope.SelectionDock(
    select: MultiSelect,
    backdrop: Backdrop,
    canDrop: Boolean,
    favoriteOn: Boolean,
    watchedOn: Boolean,
    canMore: Boolean,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onFavorite: () -> Unit,
    onWatched: () -> Unit,
    onMore: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    val primary = MaterialTheme.colorScheme.primary
    val origin = TransformOrigin(1f, 0f)
    AnimatedVisibility(
        visible = select.phase != MultiSelect.Phase.IDLE && !select.dropping,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(top = 10.dp, end = 16.dp)
            .graphicsLayer {
                // «Сброс»: док улетает вверх вместе со стопкой.
                translationY = -select.eject.value * 220.dp.toPx()
                alpha = 1f - 0.8f * select.eject.value
            },
        enter = fadeIn(MotionTokens.tweenFast()) + scaleIn(MotionTokens.menuPop(), initialScale = 0.6f, transformOrigin = origin),
        exit = fadeOut(MotionTokens.tweenFast()) + scaleOut(MotionTokens.tweenFast(), targetScale = 0.6f, transformOrigin = origin),
    ) {
        AnimatedContent(
            targetState = select.phase == MultiSelect.Phase.MOVING,
            transitionSpec = {
                (fadeIn(MotionTokens.tweenFast()) + scaleIn(MotionTokens.menuPop(), initialScale = 0.85f, transformOrigin = origin)) togetherWith
                    (fadeOut(MotionTokens.tweenFast()) + scaleOut(MotionTokens.tweenFast(), targetScale = 0.85f, transformOrigin = origin))
            },
            label = "selectionDock",
        ) { moving ->
            if (!moving) {
                Row(
                    Modifier.frostedGlass(backdrop, CircleShape, FrostedMaterials.dock()).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DockButton(R.drawable.ic_vap_trash, stringResource(R.string.multi_delete), DeleteRed, onClick = onDelete)
                    DockButton(R.drawable.ic_vap_folder_move, stringResource(R.string.multi_move), ink, onClick = onMove)
                    DockButton(R.drawable.ic_vap_star_outline, stringResource(R.string.action_favorite), if (favoriteOn) primary else ink, onClick = onFavorite)
                    DockButton(R.drawable.ic_vap_eye, stringResource(R.string.action_mark_watched), if (watchedOn) primary else ink, onClick = onWatched)
                    if (canMore) DockButton(R.drawable.ic_vap_more, stringResource(R.string.multi_more), ink, onClick = onMore)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Bubble(R.drawable.ic_vap_check, stringResource(R.string.multi_confirm_move), WatchedGreen, backdrop, dimmed = !canDrop, onClick = onConfirm)
                    Bubble(R.drawable.ic_vap_close, stringResource(R.string.multi_cancel_move), ink, backdrop, dimmed = false, onClick = onCancel)
                }
            }
        }
    }
}

/**
 * Уведомление о результате: тёмная стеклянная капсула над доком, как остальные плавающие элементы, а не
 * белый системный тост. [text] == null — скрыто; последний текст держится до конца анимации ухода.
 */
@Composable
fun BoxScope.GlassToast(text: String?, backdrop: Backdrop) {
    var shown by remember { mutableStateOf("") }
    if (text != null) shown = text
    AnimatedVisibility(
        visible = text != null,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 96.dp),
        enter = fadeIn(MotionTokens.tweenFast()) + slideInVertically(MotionTokens.sheetPresent()) { it / 2 },
        exit = fadeOut(MotionTokens.tweenFast()) + slideOutVertically(MotionTokens.tweenFast()) { it / 2 },
    ) {
        Box(
            Modifier
                .frostedGlass(backdrop, CircleShape, FrostedMaterials.dock())
                .padding(horizontal = 22.dp, vertical = 13.dp),
        ) {
            Text(
                shown,
                color = MaterialTheme.colorScheme.onBackground,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DockButton(icon: Int, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun Bubble(icon: Int, label: String, tint: Color, backdrop: Backdrop, dimmed: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .graphicsLayer { alpha = if (dimmed) 0.45f else 1f }
            .frostedGlass(backdrop, CircleShape, FrostedMaterials.dock())
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
    }
}

/**
 * Стопка миниатюр выбранных видео: летит с карточек в левый верхний угол (под кнопку «назад»), остаётся поверх
 * библиотеки, при принятии переноса садится в сетку папки, при отмене улетает вверх за экран.
 */
@Composable
fun SelectionFlight(select: MultiSelect, modifier: Modifier = Modifier) {
    val shown = select.selection.take(MAX_STACK)
    if (!(select.phase == MultiSelect.Phase.MOVING || select.dropping || select.ejecting) || shown.isEmpty()) return
    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.getTop(density)
    val slots = remember(topInset, density.density, shown.size) {
        with(density) {
            shown.indices.map { i ->
                val x = 16.dp.toPx() + i * 6.dp.toPx()
                val y = topInset + 70.dp.toPx() + i * 5.dp.toPx()
                Rect(x, y, x + StackThumbSize.toPx(), y + StackThumbSize.toPx())
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        // Нижние рисуются первыми: самая первая выбранная лежит сверху стопки.
        for (i in shown.indices.reversed()) {
            key(shown[i].key) { StackThumb(i, shown[i], slots[i], select, select.selection.size) }
        }
    }
}

@Composable
private fun StackThumb(index: Int, file: VideoFile, slot: Rect, select: MultiSelect, total: Int) {
    val context = LocalContext.current
    val frame by produceState(
        ThumbnailCache.peek(file, ThumbTier.GRID) ?: ThumbnailCache.peek(file, ThumbTier.CARD),
        file.thumbKey.id,
    ) { value = ThumbnailCache.frame(context, file, ThumbTier.GRID) }
    val image = remember(frame) { frame?.asImageBitmap() }
    val brush = remember(image) { image?.let { ShaderBrush(ImageShader(it, TileMode.Clamp, TileMode.Clamp)) } }
    val pose = StackPoses[index % StackPoses.size]
    val paper = paperColor(index, isAppInDarkTheme())

    fun rect(): Rect {
        val size = select.root?.size
        // Нет координат карточки или цели (она вне экрана): стартуем и садимся из центра экрана.
        val center = if (size != null) Rect(Offset(size.width / 2f - slot.width / 2f, size.height / 2f - slot.height / 2f), slot.size) else slot
        return if (select.dropping) {
            lerp(slot, select.dropTo.getOrNull(index) ?: center, select.drop.value)
        } else {
            lerp(select.gatherFrom.getOrNull(index) ?: center, slot, select.gather.value)
        }
    }

    Box(
        Modifier
            .layout { measurable, _ ->
                val r = rect()
                val w = r.width.roundToInt().coerceAtLeast(1)
                val h = r.height.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
            }
            .graphicsLayer {
                val settle = if (select.dropping) 1f - select.drop.value else select.gather.value
                rotationZ = pose * settle
                translationY = -select.eject.value * (slot.bottom + 60.dp.toPx() + index * 14.dp.toPx())
                alpha = if (select.dropping) 1f - ((select.drop.value - 0.75f) / 0.25f).coerceIn(0f, 1f) else 1f
            },
    ) {
        // Скругление — закрашенный скруглённый прямоугольник с кистью-шейдером, а не обрезка слоем: у обрезки на
        // каждом кадре полёта менялся бы размер, и Skia заново растрил бы маску (см. FlyingPaper).
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                val growth = if (select.dropping) select.drop.value else 0f
                val radius = lerp(6.dp.toPx(), 16.dp.toPx(), growth)
                drawRoundRect(paper, cornerRadius = CornerRadius(radius))
                if (image != null && brush != null) {
                    val s = maxOf(size.width / image.width, size.height / image.height)
                    val dx = (size.width - image.width * s) / 2f
                    val dy = (size.height - image.height * s) / 2f
                    withTransform({
                        translate(dx, dy)
                        scale(s, s, Offset.Zero)
                    }) {
                        drawRoundRect(
                            brush = brush,
                            topLeft = Offset(-dx / s, -dy / s),
                            size = androidx.compose.ui.geometry.Size(size.width / s, size.height / s),
                            cornerRadius = CornerRadius(radius / s),
                        )
                    }
                }
            },
        )
        if (index == 0 && total > 1) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(WatchedGreen)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(total.toString(), color = Color.White, fontFamily = SnProFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 12.sp)
            }
        }
    }
}
