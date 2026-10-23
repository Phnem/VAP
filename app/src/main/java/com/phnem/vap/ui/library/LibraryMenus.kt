package com.phnem.vap.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.phnem.vap.R
import com.phnem.vap.data.PlaylistSummary
import com.phnem.vap.library.VideoFile
import com.phnem.vap.library.VideoFolder
import com.phnem.vap.ui.glass.FrostedMaterials
import com.phnem.vap.ui.glass.frostedGlass
import com.phnem.vap.ui.theme.MotionTokens
import com.phnem.vap.ui.theme.SnProFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Что сейчас открыто поверх библиотеки: одно меню или диалог за раз. */
sealed interface Overlay {
    data class FolderMenu(val folder: VideoFolder) : Overlay
    data class FileMenu(val file: VideoFile) : Overlay
    data object ScopeMenu : Overlay
    data object NetworkSources : Overlay
    data class SortMenu(val forFiles: Boolean) : Overlay
    data class PlaylistPicker(val files: List<VideoFile>) : Overlay
    data class NewPlaylist(val files: List<VideoFile>) : Overlay
    data class RenamePlaylist(val id: Long, val current: String) : Overlay
    data class PlaylistMenu(val playlist: PlaylistSummary) : Overlay
    data object QueueMenu : Overlay
}

/**
 * Нижний лист из матового стекла (design-spec §4, п. 5): затемнение, пружинный выезд, закрытие
 * тапом мимо или кнопкой «назад». Стекло размывает то, что собирает [backdrop].
 */
@Composable
fun GlassSheet(
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    // Меню библиотеки — шторка Vetro, как остальные панели (docs/ui-kit.md): почти непрозрачная
    // подложка. Прежняя шторка из матового стекла просвечивала содержимое сквозь текст пунктов.
    // [backdrop] больше не нужен, параметр оставлен, чтобы не менять вызовы.
    com.phnem.vap.ui.components.VapSheet(
        onDismiss = onDismiss,
        isDark = com.phnem.vap.ui.theme.isAppInDarkTheme(),
        immersive = false,
    ) { close ->
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            content(close)
        }
    }
}

@Composable
fun SheetTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        fontFamily = SnProFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

/** Строка действия. [checked] ставит галочку справа (выбранный режим), [trailing] — короткую подпись. */
@Composable
fun SheetItem(
    label: String,
    onClick: () -> Unit,
    icon: Int? = null,
    checked: Boolean = false,
    destructive: Boolean = false,
    trailing: String? = null,
) {
    val ink = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics { role = Role.Button }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(
            label,
            color = ink,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(trailing, color = ink.copy(alpha = 0.5f), fontFamily = SnProFamily, fontSize = 15.sp)
        }
        if (checked) {
            Spacer(Modifier.width(8.dp))
            Icon(
                painterResource(R.drawable.ic_vap_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Лист с одним полем ввода: новое имя плейлиста или переименование. */
@Composable
fun NameSheet(
    backdrop: Backdrop,
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val ink = MaterialTheme.colorScheme.onBackground
    GlassSheet(backdrop = backdrop, onDismiss = onDismiss) { _ ->
        SheetTitle(title)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(CircleShape)
                .background(ink.copy(alpha = 0.08f))
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            if (text.isEmpty()) {
                Text(
                    stringResource(R.string.playlist_name_hint),
                    color = ink.copy(alpha = 0.4f),
                    fontFamily = SnProFamily,
                    fontSize = 16.sp,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = ink, fontFamily = SnProFamily, fontSize = 16.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        val enabled = text.isNotBlank()
        val bg by animateColorAsState(
            targetValue = if (enabled) MaterialTheme.colorScheme.primary else ink.copy(alpha = 0.12f),
            animationSpec = MotionTokens.tweenFast(),
            label = "nameConfirm",
        )
        Box(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .clip(CircleShape)
                .background(bg)
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onConfirm(text.trim()) }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                confirmLabel,
                color = if (enabled) Color.White else ink.copy(alpha = 0.4f),
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }
    }
}
