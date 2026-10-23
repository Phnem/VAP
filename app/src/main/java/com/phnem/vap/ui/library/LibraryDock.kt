package com.phnem.vap.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import kotlin.math.max
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.phnem.vap.R
import com.phnem.vap.ui.glass.FrostedMaterials
import com.phnem.vap.ui.glass.frostedGlass
import com.phnem.vap.ui.theme.MotionTokens
import com.phnem.vap.ui.theme.SnProFamily

/**
 * Нижняя плавающая зона (design-spec §3.5): слева переключатель «Сетка | Список», справа круглая
 * кнопка поиска. Оба — матовое стекло над содержимым, которое [backdrop] собирает с экрана.
 */
@Composable
fun BoxScope.LibraryDock(
    backdrop: Backdrop,
    grid: Boolean,
    onGrid: (Boolean) -> Unit,
    searchActive: Boolean,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .frostedGlass(backdrop, CircleShape, FrostedMaterials.dock())
                .padding(4.dp),
        ) {
            Segment(R.drawable.ic_vap_grid, stringResource(R.string.view_grid), selected = grid) { onGrid(true) }
            Segment(R.drawable.ic_vap_list, stringResource(R.string.view_list), selected = !grid) { onGrid(false) }
        }
        val description = stringResource(R.string.search)
        Box(
            Modifier
                .size(56.dp)
                .frostedGlass(backdrop, CircleShape, FrostedMaterials.dock())
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSearch,
                )
                .semantics { contentDescription = description; role = Role.Button; selected = searchActive },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_vap_search),
                contentDescription = null,
                tint = if (searchActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Строка поиска: стеклянная капсула, которая плавает над доком, а при открытой клавиатуре встаёт
 * прямо над ней. Раньше поле было первым элементом списка: при прокрученном списке его не было
 * видно, а клавиатура его не поднимала.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BoxScope.LibrarySearchBar(
    visible: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
    backdrop: Backdrop,
    hasPlayback: Boolean,
    modifier: Modifier = Modifier,
) {
    val imeInsets = WindowInsets.ime
    val navInsets = WindowInsets.navigationBars
    AnimatedVisibility(
        visible = visible,
        modifier = modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .offset {
                val ime = imeInsets.getBottom(this)
                val nav = navInsets.getBottom(this)
                // Над доком (и над мини-плеером, если он есть) или над клавиатурой — что выше.
                val resting = nav + (88.dp + if (hasPlayback) 64.dp else 0.dp).roundToPx()
                IntOffset(0, -max(ime + 8.dp.roundToPx(), resting))
            },
        enter = fadeIn(MotionTokens.tweenFast()) + slideInVertically(MotionTokens.sheetPresent()) { it / 2 },
        exit = fadeOut(MotionTokens.tweenFast()) + slideOutVertically(MotionTokens.tweenFast()) { it / 2 },
    ) {
        val ink = MaterialTheme.colorScheme.onBackground
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val closeDescription = stringResource(R.string.close)
        LaunchedEffect(Unit) { focus.requestFocus() }
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .frostedGlass(backdrop, CircleShape, FrostedMaterials.dock())
                .padding(start = 18.dp, end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_vap_search),
                contentDescription = null,
                tint = ink.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp),
            )
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.search_hint),
                        color = ink.copy(alpha = 0.5f),
                        fontFamily = SnProFamily,
                        fontSize = 16.sp,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = ink, fontFamily = SnProFamily, fontSize = 16.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(ink.copy(alpha = 0.10f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        keyboard?.hide()
                        onClose()
                    }
                    .semantics { contentDescription = closeDescription; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_vap_close),
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun Segment(icon: Int, label: String, selected: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onBackground
    val bg by animateColorAsState(
        targetValue = if (selected) ink.copy(alpha = 0.10f) else Color.Transparent,
        animationSpec = MotionTokens.tweenFast(),
        label = "dockSegment",
    )
    Column(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .semantics { role = Role.Tab; this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
        Text(
            text = label,
            color = ink,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
        )
    }
}
