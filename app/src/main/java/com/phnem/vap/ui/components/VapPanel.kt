package com.phnem.vap.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phnem.vap.ui.theme.BrandOrange
import com.phnem.vap.ui.theme.IosDesign
import com.phnem.vap.ui.theme.MotionTokens
import com.phnem.vap.ui.theme.SnProFamily
import com.phnem.vap.ui.theme.SquircleShape
import com.phnem.vap.util.Haptic
import com.phnem.vap.util.performHaptic
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Элементы панелей плеера в языке Vetro Collection. Образец — шторки аудиоплеера
 * (`audiobooks/ui/PlayerSheets.kt`): тёмная парящая шторка, строки-карточки `Black α0.22`,
 * капсулы-«пилюли», белая главная кнопка, `IosSwitch`, активное — оранжевое.
 * Стандартные FilterChip/Switch/Slider/OutlinedTextField/AlertDialog Material 3 в плеере не
 * используются.
 */

/** Тема панели: плеер всегда тёмный (как шторки аудиоплеера Vetro), панели библиотеки следуют теме приложения. */
/** В нижней зоне действий кнопки занимают всю ширину. */
val LocalPanelFillButtons = androidx.compose.runtime.staticCompositionLocalOf { false }

val LocalPanelDark = androidx.compose.runtime.staticCompositionLocalOf { true }

object PanelColors {
    private val dark: Boolean
        @Composable @androidx.compose.runtime.ReadOnlyComposable get() = LocalPanelDark.current

    /** Фон шторки: тёплый графит `PlayerSheetFrame` Vetro в тёмной, кремовая основа приложения в светлой. */
    val Sheet: Color @Composable @androidx.compose.runtime.ReadOnlyComposable get() = if (dark) Color(0xFF17130F) else IosDesign.sheetSurface(false)
    val Ink: Color @Composable @androidx.compose.runtime.ReadOnlyComposable get() = if (dark) Color.White else Color(0xFF1A1A1A)
    val Muted: Color @Composable @androidx.compose.runtime.ReadOnlyComposable get() = if (dark) Color.White.copy(alpha = 0.6f) else Color(0xFF4B4541)
    val Card: Color @Composable @androidx.compose.runtime.ReadOnlyComposable get() = if (dark) Color.Black.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.72f)
    val CardActive: Color @Composable @androidx.compose.runtime.ReadOnlyComposable get() = if (dark) Color.Black.copy(alpha = 0.34f) else Color.White
    val Chip: Color @Composable @androidx.compose.runtime.ReadOnlyComposable get() = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)

    /** Заливка выбранной «пилюли» и главной кнопки (белая на тёмном, графит на светлом) и текст на ней. */
    val Inverse: Color @Composable @androidx.compose.runtime.ReadOnlyComposable get() = if (dark) Color.White else Color(0xFF1A1A1A)
    val OnInverse: Color @Composable @androidx.compose.runtime.ReadOnlyComposable get() = if (dark) Color(0xFF111111) else Color.White

    /** Заливка/форма акцента и акцент для текста: `#E85002` годится как форма, для мелкого текста на светлом нужен тёмный тон. */
    val Accent: Color get() = BrandOrange
    val AccentText: Color @Composable @androidx.compose.runtime.ReadOnlyComposable get() = if (dark) BrandOrange else Color(0xFF8F2D00)

    /** Читаемый статусный красный на текущей поверхности и заливка опасной кнопки. */
    val Danger: Color @Composable @androidx.compose.runtime.ReadOnlyComposable get() = if (dark) Color(0xFFFF8A80) else Color(0xFF9C1F18)
    val DangerFill: Color get() = Color(0xFFC10801)
}

@Composable
fun PanelTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(text, color = PanelColors.Ink, fontFamily = SnProFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp,
            modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = PanelColors.Muted, fontFamily = SnProFamily, fontSize = 14.sp)
    }
}

/** Шапка панели с отступами под ручку шторки; [onClose] рисует компактную капсулу закрытия справа. */
@Composable
fun PanelHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null, onClose: (() -> Unit)? = null,
    closeLabel: String = androidx.compose.ui.res.stringResource(com.phnem.vap.R.string.close)) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        PanelTitle(title, Modifier.weight(1f), trailing)
        if (onClose != null) {
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.defaultMinSize(minHeight = 40.dp).clip(CircleShape).background(PanelColors.Chip)
                    .clickable(onClick = onClose).semantics { role = Role.Button }.padding(horizontal = 16.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) { Text(closeLabel, color = PanelColors.Ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1) }
        }
    }
}

/**
 * Кнопка закрытия, плавающая над прокручиваемым содержимым шторки (без шапки-подложки). Заливка
 * непрозрачная: подложка шторки плюс [PanelColors.Chip], иначе строки под ней просвечивали бы.
 */
@Composable
fun PanelFloatingClose(onClick: () -> Unit, modifier: Modifier = Modifier,
    label: String = androidx.compose.ui.res.stringResource(com.phnem.vap.R.string.close)) {
    // Шторка на экране светлее, чем PanelColors.Sheet (поверх неё рисуется контейнер), поэтому цвет «Chip поверх
    // шторки» задан готовым непрозрачным значением, как у прежней кнопки в шапке.
    val chip = if (LocalPanelDark.current) Color(0xFF3B3834) else lerp(IosDesign.sheetSurface(false), Color.Black, 0.06f)
    Box(
        modifier.defaultMinSize(minHeight = 40.dp).clip(CircleShape).background(chip)
            .clickable(onClick = onClick).semantics { role = Role.Button }.padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = PanelColors.Ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1) }
}

/** Заголовок группы, как `IosSectionHeader` Collection, но без капса: русские фразы капсом нечитаемы. */
@Composable
fun PanelSection(text: String, modifier: Modifier = Modifier) {
    Text(text, color = PanelColors.Muted, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        modifier = modifier.padding(start = 4.dp, top = 6.dp))
}

@Composable
fun PanelText(text: String, modifier: Modifier = Modifier, color: Color = PanelColors.Ink, size: Int = 15, bold: Boolean = false) {
    Text(text, color = color, fontFamily = SnProFamily, fontSize = size.sp, lineHeight = (size * 1.35f).sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, modifier = modifier)
}

@Composable
fun PanelHint(text: String, modifier: Modifier = Modifier) =
    Text(text, color = PanelColors.Muted, fontFamily = SnProFamily, fontSize = 12.sp, lineHeight = 16.sp, modifier = modifier)

@Composable
fun PanelError(text: String, modifier: Modifier = Modifier) {
    Text(text, color = PanelColors.Danger, fontFamily = SnProFamily, fontSize = 13.sp, lineHeight = 18.sp, modifier = modifier)
}

/** Прокручиваемое тело панели: заголовок и вкладки остаются на месте. */
@Composable
fun ColumnScope.PanelBody(modifier: Modifier = Modifier, scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .weight(1f, fill = false)
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Капсула-выбор (как `Pill` Collection): выбранная — белая, остальные — `White α0.08`. */
@Composable
fun PanelPill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    accent: Boolean = false) {
    val bg by animateColorAsState(
        when {
            selected && accent -> PanelColors.Accent
            selected -> PanelColors.Inverse
            else -> PanelColors.Chip
        }, MotionTokens.tweenFast(), label = "pillBg",
    )
    val ink = when {
        !enabled -> PanelColors.Muted.copy(alpha = 0.5f)
        selected && accent -> Color.White
        selected -> PanelColors.OnInverse
        else -> PanelColors.Muted
    }
    Box(
        modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.selected = selected; role = Role.RadioButton }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** Ряд вариантов с горизонтальной прокруткой. */
@Composable
fun PanelPillRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Вкладки панели: прокручиваемый ряд капсул под заголовком. */
@Composable
fun PanelTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    PanelPillRow(modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp)) {
        titles.forEachIndexed { index, title -> PanelPill(title, index == selected, { onSelect(index) }) }
    }
}

/** Кнопка-капсула. [primary] — белая, как «Start» в референсе; иначе мягкая; [danger] — красная пара. */
@Composable
fun PanelButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    primary: Boolean = false, danger: Boolean = false) {
    val bg = when {
        !enabled -> PanelColors.Chip
        danger -> PanelColors.DangerFill
        primary -> PanelColors.Inverse
        else -> PanelColors.Chip
    }
    val ink = when {
        !enabled -> PanelColors.Muted.copy(alpha = 0.55f)
        danger -> Color.White
        primary -> PanelColors.OnInverse
        else -> PanelColors.Ink
    }
    Box(
        (if (LocalPanelFillButtons.current) Modifier.fillMaxWidth() else Modifier).then(modifier)
            .defaultMinSize(minHeight = 52.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button; if (!enabled) disabled() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold,
            fontSize = if (primary) 17.sp else 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Карточка-группа (`ActionRow`/`NavRow` Collection): скругление 18, фон `Black α0.22`. */
@Composable
fun PanelCard(modifier: Modifier = Modifier, active: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (active) PanelColors.CardActive else PanelColors.Card)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Строка действия или выбора: название, подпись, значение справа и точка-индикатор у выбранной. */
@Composable
fun PanelRow(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null,
    value: String? = null, selected: Boolean = false, enabled: Boolean = true, danger: Boolean = false) {
    val ink = when {
        !enabled -> PanelColors.Muted
        danger -> PanelColors.Danger
        else -> PanelColors.Ink
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) PanelColors.CardActive else PanelColors.Card)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Button; this.selected = selected; if (!enabled) disabled()
                // Заголовок — имя элемента, подпись и значение — состояние: объединённый узел не склеивает их в один текст.
                contentDescription = title
                listOfNotNull(subtitle, value).joinToString(", ").takeIf { it.isNotEmpty() }?.let { stateDescription = it }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = PanelColors.Muted, fontFamily = SnProFamily, fontSize = 12.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            Text(value, color = PanelColors.Muted, fontFamily = SnProFamily, fontSize = 14.sp, maxLines = 1)
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            // Выбранное отмечено не только цветом фона, но и точкой, как у оглавления Collection.
            Box(Modifier.size(8.dp).clip(CircleShape).background(PanelColors.Accent))
        }
    }
}

/** Строка с переключателем (`SettingRow` Collection). Нажимается вся строка. */
@Composable
fun PanelSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
    hint: String? = null, enabled: Boolean = true) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PanelColors.Card)
            .clickable(enabled = enabled) { onChange(!checked) }
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                contentDescription = title
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                if (!enabled) disabled()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = if (enabled) PanelColors.Ink else PanelColors.Muted, fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            IosSwitch(checked = checked)
        }
        if (hint != null) PanelHint(hint, Modifier.padding(top = 2.dp))
    }
}

/**
 * Ползунок в языке приложения: трек-капсула, оранжевая заливка, бегунок-капсула. Вместо M3 `Slider`.
 * [onValueChangeFinished] вызывается один раз при отпускании. Значение доступно скринридеру.
 */
@Composable
fun PanelSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    steps: Int = 0,
    description: String = "",
    onValueChangeFinished: () -> Unit = {},
) {
    val view = LocalView.current
    val currentValue by rememberUpdatedState(value)
    val currentChange by rememberUpdatedState(onValueChange)
    val currentFinished by rememberUpdatedState(onValueChangeFinished)
    val span = (range.endInclusive - range.start).coerceAtLeast(1e-6f)
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    val thumbWidth = 34.dp
    val trackHeight = 28.dp
    fun snap(raw: Float): Float {
        val clamped = raw.coerceIn(range.start, range.endInclusive)
        if (steps <= 0) return clamped
        val step = span / (steps + 1)
        return (range.start + ((clamped - range.start) / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)
    }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics {
                if (description.isNotBlank()) contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range.start, range.endInclusive), range, steps)
                if (enabled) setProgress { target -> currentChange(snap(target)); currentFinished(); true } else disabled()
            }
            .pointerInput(enabled, range, steps) {
                if (!enabled) return@pointerInput
                val thumbPx = thumbWidth.toPx()
                fun at(x: Float): Float {
                    val usable = (size.width - thumbPx).coerceAtLeast(1f)
                    return snap(range.start + ((x - thumbPx / 2f) / usable).coerceIn(0f, 1f) * span)
                }
                var last = currentValue
                fun emit(next: Float) {
                    if (next != last) {
                        if (steps > 0) performHaptic(view, Haptic.Tick)
                        last = next; currentChange(next)
                    }
                }
                // Тап переносит бегунок под палец; горизонтальный жест ведёт его; вертикальный уходит прокрутке панели.
                detectTapGestures(onTap = { offset ->
                    last = currentValue; emit(at(offset.x)); currentFinished()
                })
            }
            .pointerInput(enabled, range, steps) {
                if (!enabled) return@pointerInput
                val thumbPx = thumbWidth.toPx()
                fun at(x: Float): Float {
                    val usable = (size.width - thumbPx).coerceAtLeast(1f)
                    return snap(range.start + ((x - thumbPx / 2f) / usable).coerceIn(0f, 1f) * span)
                }
                var last = currentValue
                fun emit(next: Float) {
                    if (next != last) {
                        if (steps > 0) performHaptic(view, Haptic.Tick)
                        last = next; currentChange(next)
                    }
                }
                detectHorizontalDragGestures(
                    onDragStart = { offset -> last = currentValue; emit(at(offset.x)) },
                    onDragEnd = { currentFinished() },
                    onDragCancel = { currentFinished() },
                ) { change, _ -> change.consume(); emit(at(change.position.x)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val usable = maxWidth - thumbWidth
        val dim = if (enabled) 1f else 0.4f
        Box(Modifier.fillMaxWidth().height(trackHeight).align(Alignment.Center).clip(CircleShape).background(PanelColors.Ink.copy(alpha = 0.10f * dim)))
        Box(
            Modifier.width(thumbWidth + usable * fraction).height(trackHeight).align(Alignment.CenterStart)
                .clip(CircleShape).background(PanelColors.Accent.copy(alpha = if (enabled) 0.9f else 0.35f)),
        )
        Box(
            Modifier.offset(x = usable * fraction).width(thumbWidth).height(trackHeight).clip(CircleShape)
                .background(if (enabled) Color.White else Color.White.copy(alpha = 0.5f)),
        )
    }
}

/** Подписанное число и ползунок в одной карточке, как «скорость» в шторках Collection. */
@Composable
fun PanelSliderCard(label: String, valueText: String, value: Float, onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier, steps: Int = 0, enabled: Boolean = true,
    hint: String? = null, onValueChangeFinished: () -> Unit = {}) {
    PanelCard(modifier) {
        // Подпись и значение озвучивает сам ползунок (описание + диапазон): дублирующие узлы скрыты.
        Row(Modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = PanelColors.Ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                modifier = Modifier.weight(1f))
            Text(valueText, color = PanelColors.AccentText, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        PanelSlider(value, onValueChange, range, steps = steps, enabled = enabled, description = label,
            onValueChangeFinished = onValueChangeFinished)
        if (hint != null) PanelHint(hint)
    }
}

/**
 * Поле ввода (`AnimatedOneUiTextField` Collection): подпись над полем, рамка 1.5dp становится
 * оранжевой в фокусе. Подпись остаётся видимой, placeholder её не заменяет.
 */
@Composable
fun PanelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    error: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    showLabel: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(
        when {
            error -> PanelColors.Danger
            focused -> PanelColors.Accent
            else -> PanelColors.Ink.copy(alpha = 0.16f)
        }, MotionTokens.tweenFast(), label = "fieldBorder",
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (showLabel) Text(label, color = PanelColors.Muted, fontFamily = SnProFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp,
            modifier = Modifier.padding(start = 4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = TextStyle(color = if (enabled) PanelColors.Ink else PanelColors.Muted, fontFamily = SnProFamily, fontSize = 16.sp),
            cursorBrush = SolidColor(PanelColors.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onGo = { onImeAction?.invoke() }, onDone = { onImeAction?.invoke() }, onSearch = { onImeAction?.invoke() }),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(16.dp))
                .background(PanelColors.Card)
                .border(BorderStroke(1.5.dp, border), RoundedCornerShape(16.dp))
                .semantics { contentDescription = label }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && (placeholder.isNotEmpty() || !showLabel)) {
                        Text(placeholder.ifEmpty { label }, color = PanelColors.Ink.copy(alpha = 0.38f), fontFamily = SnProFamily, fontSize = 16.sp)
                    }
                    inner()
                }
            },
        )
    }
}

/** Полоса прогресса: капсула 6dp, оранжевая заливка. */
@Composable
fun PanelProgress(progress: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), MotionTokens.tweenFast(), label = "panelProgress")
    Box(
        modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(PanelColors.Ink.copy(alpha = 0.14f))
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f) },
    ) {
        Box(Modifier.fillMaxWidth(animated).height(6.dp).clip(CircleShape).background(PanelColors.Accent))
    }
}

/** Неопределённый прогресс: бегущий сегмент по треку. */
@Composable
fun PanelBusy(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "panelBusy")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "panelBusyPhase")
    BoxWithConstraints(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(PanelColors.Ink.copy(alpha = 0.14f))) {
        val segment = maxWidth * 0.3f
        Box(
            Modifier.offset { IntOffset(((maxWidth.toPx() + segment.toPx()) * phase - segment.toPx()).roundToInt(), 0) }
                .width(segment).height(4.dp).clip(CircleShape).background(PanelColors.Accent),
        )
    }
}

/**
 * iOS-переключатель Collection (`IosSwitch`, 51×31): ползунок скользит и вытягивается капсулой
 * в середине хода, трек плавно перекрашивается. Нажатие обрабатывает охватывающая строка
 * ([PanelSwitchRow]) либо [onCheckedChange].
 */
@Composable
fun IosSwitch(checked: Boolean, modifier: Modifier = Modifier, onColor: Color = BrandOrange,
    onCheckedChange: ((Boolean) -> Unit)? = null) {
    val progress by animateFloatAsState(if (checked) 1f else 0f, MotionTokens.menuPop(), label = "iosSwitchProgress")
    val p = progress.coerceIn(0f, 1f)
    val trackColor = lerp(Color(0xFF787880).copy(alpha = 0.32f), onColor, p)
    val thumbDiameter = 27.dp
    val travel = 20.dp
    val stretch = sin(p * PI.toFloat()).coerceIn(0f, 1f)
    val thumbWidth = thumbDiameter + 9.dp * stretch
    val thumbCenterX = thumbDiameter / 2 + travel * progress
    Box(
        modifier = modifier
            .size(width = 51.dp, height = 31.dp)
            .clip(SquircleShape(15.5.dp, smoothing = 0.4f))
            .background(trackColor)
            .then(
                if (onCheckedChange != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = { onCheckedChange(!checked) },
                ).semantics { role = Role.Switch; toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
                else Modifier,
            )
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.offset(x = thumbCenterX - thumbWidth / 2).size(width = thumbWidth, height = thumbDiameter)
                .clip(SquircleShape(thumbDiameter / 2, smoothing = 0.4f)).background(Color.White),
        )
    }
}

/** Шаги значения (− значение +) для небольших целых, где ползунок избыточен. */
@Composable
fun PanelStepper(label: String, valueText: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier,
    canMinus: Boolean = true, canPlus: Boolean = true) {
    PanelCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = PanelColors.Ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                modifier = Modifier.weight(1f))
            PanelPill("−", false, onMinus, enabled = canMinus, modifier = Modifier.semantics { contentDescription = "$label −" })
            Text(valueText, color = PanelColors.AccentText, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 12.dp))
            PanelPill("+", false, onPlus, enabled = canPlus, modifier = Modifier.semantics { contentDescription = "$label +" })
        }
    }
}

/** Нижняя зона действий панели: кнопки на всю ширину. */
@Composable
fun PanelFooter(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPanelFillButtons provides true) {
        Column(modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
