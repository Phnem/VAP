package com.phnem.vap.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phnem.vap.R
import com.phnem.vap.ui.components.PanelColors
import com.phnem.vap.ui.components.VapSheet
import com.phnem.vap.ui.theme.IosDesign
import com.phnem.vap.ui.theme.SnProFamily
import com.phnem.vap.ui.theme.SquircleShape
import com.phnem.vap.ui.theme.isAppInDarkTheme

/** Один вариант сортировки: [hint] показывается, пока вариант не выбран; у выбранного вместо него направление. */
class SortCard(val title: String, val hint: String, val icon: Int, val selected: Boolean, val descending: Boolean)

/**
 * Шторка сортировки в стиле Vetro Collection (`SortOptionRow`): заголовок с подписью, под ним карточки
 * с плиткой-значком, названием, пояснением и радиокнопкой. Выбранная карточка подкрашена акцентом.
 * Повторное нажатие на выбранную меняет направление; решение принимает вызывающий через [onPick].
 */
@Composable
fun SortSheet(cards: List<SortCard>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val dark = isAppInDarkTheme()
    VapSheet(onDismiss, isDark = dark, immersive = false) { close ->
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
        ) {
            Text(
                stringResource(R.string.sort), color = PanelColors.Ink, fontFamily = SnProFamily, fontWeight = FontWeight.Bold,
                fontSize = 24.sp, letterSpacing = (-0.3).sp,
            )
            Text(
                stringResource(R.string.sort_subtitle), color = PanelColors.Ink.copy(alpha = 0.5f), fontFamily = SnProFamily,
                fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
            )
            cards.forEachIndexed { index, card ->
                SortOptionCard(card, dark) { onPick(index); close() }
            }
        }
    }
}

@Composable
private fun SortOptionCard(card: SortCard, dark: Boolean, onClick: () -> Unit) {
    val accent = PanelColors.Accent
    val shape = SquircleShape(18.dp)
    val cardBg = if (card.selected) accent.copy(alpha = if (dark) 0.20f else 0.13f) else IosDesign.elevatedCard(dark)
    val wellBg = if (dark) Color.Black.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.75f)
    val titleColor = if (card.selected) PanelColors.AccentText else PanelColors.Ink
    val subtitle = when {
        !card.selected -> card.hint
        card.descending -> stringResource(R.string.sort_order_desc)
        else -> stringResource(R.string.sort_order_asc)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(shape)
            .background(cardBg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { role = Role.RadioButton; selected = card.selected }
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(SquircleShape(12.dp)).background(wellBg), contentAlignment = Alignment.Center) {
            Icon(
                painterResource(card.icon), contentDescription = null,
                tint = if (card.selected) PanelColors.AccentText else PanelColors.Muted, modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                card.title, color = titleColor, fontFamily = SnProFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, color = PanelColors.Ink.copy(alpha = 0.5f), fontFamily = SnProFamily, fontSize = 13.sp, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .then(
                    if (card.selected) Modifier.background(accent)
                    else Modifier.border(BorderStroke(2.dp, PanelColors.Ink.copy(alpha = 0.22f)), CircleShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (card.selected) Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
        }
    }
}
