package com.phnem.vap.ui.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phnem.vap.R
import com.phnem.vap.ui.components.PanelColors
import com.phnem.vap.ui.components.ProgressRing
import com.phnem.vap.ui.components.RingTone
import com.phnem.vap.ui.components.StatusSuccess
import com.phnem.vap.ui.components.VapSheet
import com.phnem.vap.ui.theme.BrandOrange
import com.phnem.vap.ui.theme.MotionTokens
import com.phnem.vap.ui.theme.SnProFamily
import com.phnem.vap.ui.theme.isAppInDarkTheme
import com.phnem.vap.update.UpdateFailure
import com.phnem.vap.update.UpdateMode
import com.phnem.vap.update.UpdateRelease
import com.phnem.vap.update.UpdateRuntime
import com.phnem.vap.update.UpdateState
import com.phnem.vap.util.Haptic
import com.phnem.vap.util.performHaptic
import java.util.Locale
import kotlin.math.roundToInt

/** Страница релизов: куда вести тех, у кого встроенное обновление выключено подписью. */
private const val RELEASES_PAGE_URL = "https://github.com/Phnem/VAP/releases"

/** Что нарисовать в центре кольца. */
private sealed interface Center {
    data class Percent(val value: Int) : Center
    data class Glyph(val icon: Int, val tint: Color) : Center
    data object Nothing : Center
}

private data class Face(
    val title: String,
    val body: String,
    val tone: RingTone,
    val fraction: Float?,
    val center: Center,
)

/**
 * Окно обновления. Одно и то же окно служит и предложением при запуске ([manual] = false), и экраном
 * «Проверка обновлений» из настроек ([manual] = true): во втором случае оно ещё и проверяет релиз само,
 * показывает версии, время проверки и сведения о подписи — для тех, кто хочет видеть, что происходит.
 *
 * Состояние берётся из менеджера, а не из окна: окно можно закрыть посреди загрузки, загрузка продолжится.
 */
@Composable
fun UpdateSheet(manual: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val kit = remember { UpdateRuntime.get(context) }
    val manager = kit.manager
    val state by manager.state.collectAsStateWithLifecycle()
    val view = LocalView.current
    val comma = LocalConfiguration.current.locales[0].language == "ru"

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        manager.onReturnedFromInstallSettings()
    }
    LaunchedEffect(manual) { if (manual) manager.checkNow() }

    val face = faceFor(state, manager.installedVersion, comma)
    val release = releaseOf(state)
    val checkedAt = (state as? UpdateState.UpToDate)?.checkedAtMillis

    VapSheet(
        onDismiss = {
            if (!manual) manager.dismissStartupOffer()
            onDismiss()
        },
        isDark = isAppInDarkTheme(),
        immersive = false,
    ) { close ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 18.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AnimatedContent(
                targetState = face.title to face.body,
                transitionSpec = { fadeIn(MotionTokens.easeEnter(durationMillis = 220)) togetherWith fadeOut(MotionTokens.easeExit()) },
                label = "updateHeader",
            ) { (title, body) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, color = PanelColors.Ink, fontFamily = SnProFamily, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(body, color = PanelColors.Muted, fontFamily = SnProFamily, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
                }
            }

            ProgressRing(fraction = face.fraction, tone = face.tone, modifier = Modifier.padding(vertical = 6.dp)) {
                // Переход по ВИДУ содержимого, а не по значению: иначе при каждом новом проценте цифры накладывались бы друг на друга.
                Crossfade(targetState = face.center::class, animationSpec = MotionTokens.tweenFast(), label = "ringCenter") {
                    when (val c = face.center) {
                        is Center.Percent -> Text("${c.value}%", color = PanelColors.Ink, fontFamily = SnProFamily, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        is Center.Glyph -> Icon(painterResource(c.icon), contentDescription = null, tint = c.tint, modifier = Modifier.size(46.dp))
                        Center.Nothing -> Spacer(Modifier.size(1.dp))
                    }
                }
            }

            (state as? UpdateState.Downloading)?.let { d ->
                Text(progressLine(d, comma), color = PanelColors.Muted, fontFamily = SnProFamily, fontSize = 13.sp, textAlign = TextAlign.Center)
            }

            if (manual || release != null) {
                VersionsCard(installed = manager.installedVersion, latest = release?.tag, checkedAt = checkedAt)
            }
            if (release != null) WhatsNew(markdown = release.changelogMarkdown)
            if (manual && manager.mode == UpdateMode.Auto) SecurityCard(fingerprint = kit.verifier.installedFingerprints().firstOrNull())

            Crossfade(targetState = state::class, animationSpec = MotionTokens.tweenStandard(), label = "updateActions") {
                Actions(
                    state = state,
                    onClose = close,
                    onUpdate = { performHaptic(view, Haptic.Light); manager.download() },
                    onInstall = { performHaptic(view, Haptic.Light); manager.install() },
                    onCancel = { manager.cancelDownload() },
                    onCheck = { performHaptic(view, Haptic.Light); manager.checkNow() },
                    onAllow = {
                        performHaptic(view, Haptic.Light)
                        permissionLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                        )
                    },
                    onOpen = { url ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    },
                )
            }
        }
    }
}

/** Строка «Проверка обновлений» для настроек: название и короткое состояние справа от него. */
@Composable
fun UpdateSettingsRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember { UpdateRuntime.get(context).manager }
    val state by manager.state.collectAsStateWithLifecycle()
    val summary = rowSummary(state, manager.installedVersion)
    val highlight = state is UpdateState.Available || state is UpdateState.Ready || state is UpdateState.NeedsPermission
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PanelColors.Card)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.upd_row_title), color = PanelColors.Ink, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(summary, color = if (highlight) PanelColors.AccentText else PanelColors.Muted, fontFamily = SnProFamily, fontSize = 12.sp, maxLines = 2)
        }
        if (highlight) Box(Modifier.padding(start = 10.dp).size(8.dp).clip(CircleShape).background(PanelColors.Accent))
    }
}

@Composable
private fun rowSummary(state: UpdateState, installed: String): String = when (state) {
    UpdateState.Unavailable -> stringResource(R.string.upd_row_unavailable)
    UpdateState.Idle -> installed
    UpdateState.Checking -> stringResource(R.string.upd_row_checking)
    is UpdateState.UpToDate -> "$installed · ${stringResource(R.string.upd_row_up_to_date)}"
    is UpdateState.Available -> stringResource(R.string.upd_row_available, state.release.tag)
    is UpdateState.Downloading -> stringResource(R.string.upd_row_downloading, (state.fraction * 100).roundToInt())
    is UpdateState.Ready, is UpdateState.NeedsPermission -> stringResource(R.string.upd_row_ready)
    is UpdateState.Installing -> stringResource(R.string.upd_installing_title)
    is UpdateState.Failed -> stringResource(R.string.upd_row_failed)
}

private fun releaseOf(state: UpdateState): UpdateRelease? = when (state) {
    is UpdateState.Available -> state.release
    is UpdateState.Downloading -> state.release
    is UpdateState.Ready -> state.release
    is UpdateState.NeedsPermission -> state.release
    is UpdateState.Installing -> state.release
    is UpdateState.Failed -> state.release
    else -> null
}

@Composable
private fun faceFor(state: UpdateState, installed: String, comma: Boolean): Face = when (state) {
    UpdateState.Unavailable -> Face(
        stringResource(R.string.upd_unavailable_title), stringResource(R.string.upd_unavailable_body), RingTone.Active, 1f,
        Center.Glyph(R.drawable.ic_vap_shield, BrandOrange),
    )
    UpdateState.Idle, UpdateState.Checking -> Face(
        stringResource(R.string.upd_checking_title), stringResource(R.string.upd_checking_body), RingTone.Active, null, Center.Nothing,
    )
    is UpdateState.UpToDate -> Face(
        stringResource(R.string.upd_up_to_date_title), stringResource(R.string.upd_up_to_date_body, installed), RingTone.Success, 1f,
        Center.Glyph(R.drawable.ic_vap_check, StatusSuccess),
    )
    is UpdateState.Available -> Face(
        stringResource(R.string.upd_available_title),
        stringResource(R.string.upd_available_body, state.release.tag, sizeLabel(state.release.sizeBytes, comma)),
        RingTone.Active, 0f,
        Center.Glyph(R.drawable.ic_vap_download, BrandOrange),
    )
    is UpdateState.Downloading -> Face(
        stringResource(R.string.upd_downloading_title), stringResource(R.string.upd_downloading_hint), RingTone.Active, state.fraction,
        Center.Percent((state.fraction * 100).roundToInt()),
    )
    is UpdateState.Ready -> Face(
        stringResource(R.string.upd_ready_title), stringResource(R.string.upd_ready_body, state.release.tag), RingTone.Success, 1f,
        Center.Glyph(R.drawable.ic_vap_check, StatusSuccess),
    )
    is UpdateState.NeedsPermission -> Face(
        stringResource(R.string.upd_permission_title), stringResource(R.string.upd_permission_body), RingTone.Active, 1f,
        Center.Glyph(R.drawable.ic_vap_shield, BrandOrange),
    )
    is UpdateState.Installing -> Face(
        stringResource(R.string.upd_installing_title), stringResource(R.string.upd_installing_body), RingTone.Active, null, Center.Nothing,
    )
    is UpdateState.Failed -> {
        val (title, body) = when (state.reason) {
            UpdateFailure.CHECK -> R.string.upd_check_failed_title to R.string.upd_check_failed_body
            UpdateFailure.DOWNLOAD -> R.string.upd_download_failed_title to R.string.upd_download_failed_body
            UpdateFailure.VERIFY -> R.string.upd_verify_failed_title to R.string.upd_verify_failed_body
            UpdateFailure.INSTALL -> R.string.upd_install_failed_title to R.string.upd_install_failed_body
        }
        Face(stringResource(title), stringResource(body), RingTone.Error, 1f, Center.Glyph(R.drawable.ic_vap_alert, BrandOrange))
    }
}

/** «42» для крупных значений и «2,4» / «2.4» для мелких: знак после запятой нужен, пока число однозначное. */
internal fun megabytes(bytes: Long, decimalComma: Boolean): String {
    val mb = bytes.coerceAtLeast(0L) / 1_000_000.0
    val text = if (mb >= 10.0) mb.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", mb)
    return if (decimalComma) text.replace('.', ',') else text
}

@Composable
private fun sizeLabel(bytes: Long, comma: Boolean): String =
    if (bytes > 0) "${megabytes(bytes, comma)} ${stringResource(R.string.upd_megabyte)}" else "—"

@Composable
private fun progressLine(d: UpdateState.Downloading, comma: Boolean): String = buildString {
    append(megabytes(d.doneBytes, comma))
    if (d.totalBytes > 0) append(" / ").append(megabytes(d.totalBytes, comma))
    append(' ').append(stringResource(R.string.upd_megabyte))
    if (d.bytesPerSecond > 0) append("  ·  ").append(megabytes(d.bytesPerSecond, comma)).append(' ').append(stringResource(R.string.upd_per_second))
}

@Composable
private fun InfoCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PanelColors.Card)
            .animateContentSize(MotionTokens.tweenStandard()),
    ) { content() }
}

@Composable
private fun VersionsCard(installed: String, latest: String?, checkedAt: Long?) {
    InfoCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VersionColumn(stringResource(R.string.upd_installed), installed, Modifier.weight(1f))
                if (latest != null) {
                    Text("→", color = PanelColors.Muted, fontFamily = SnProFamily, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 10.dp))
                    VersionColumn(stringResource(R.string.upd_latest), latest, Modifier.weight(1f), accent = true)
                }
            }
            checkedAt?.let {
                Text("${stringResource(R.string.upd_last_check)}: ${relativeTime(it)}", color = PanelColors.Muted, fontFamily = SnProFamily, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun VersionColumn(label: String, value: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Column(modifier) {
        Text(label, color = PanelColors.Muted, fontFamily = SnProFamily, fontSize = 12.sp)
        Text(
            value,
            color = if (accent) PanelColors.AccentText else PanelColors.Ink,
            fontFamily = SnProFamily, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
    }
}

@Composable
private fun relativeTime(millis: Long): String {
    if (millis <= 0L) return stringResource(R.string.upd_never_checked)
    val minutes = ((System.currentTimeMillis() - millis) / 60_000L).toInt().coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(R.string.upd_just_now)
        minutes < 60 -> stringResource(R.string.upd_minutes_ago, minutes)
        minutes < 60 * 24 -> stringResource(R.string.upd_hours_ago, minutes / 60)
        else -> stringResource(R.string.upd_days_ago, minutes / (60 * 24))
    }
}

@Composable
private fun WhatsNew(markdown: String?) {
    var expanded by remember { mutableStateOf(false) }
    val view = LocalView.current
    InfoCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    performHaptic(view, Haptic.Light)
                    expanded = !expanded
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.upd_whats_new), color = PanelColors.Ink, fontFamily = SnProFamily,
                fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
            )
            Icon(
                painterResource(R.drawable.ic_vap_chevron_down), contentDescription = null, tint = PanelColors.Muted,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
        if (expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!markdown.isNullOrBlank()) {
                    MarkdownLines(markdown)
                } else {
                    Text(stringResource(R.string.upd_whats_new_empty), color = PanelColors.Muted, fontFamily = SnProFamily, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * Описание релиза с GitHub — это Markdown. Целая библиотека разметки ради нескольких строк не нужна:
 * заголовки, маркированные списки, **жирный** и `код` показываются, остальное идёт простым текстом.
 */
@Composable
private fun MarkdownLines(markdown: String) {
    markdown.lines().forEach { raw ->
        val line = raw.trimEnd()
        when {
            line.isBlank() -> Spacer(Modifier.height(2.dp))
            line.startsWith("#") -> Text(
                inline(line.trimStart('#').trim()), color = PanelColors.Ink, fontFamily = SnProFamily,
                fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp),
            )
            line.trimStart().let { it.startsWith("- ") || it.startsWith("* ") } -> Row {
                Text("•", color = PanelColors.Muted, fontFamily = SnProFamily, fontSize = 13.sp, modifier = Modifier.width(14.dp))
                Text(inline(line.trimStart().drop(2)), color = PanelColors.Muted, fontFamily = SnProFamily, fontSize = 13.sp, lineHeight = 18.sp)
            }
            else -> Text(inline(line), color = PanelColors.Muted, fontFamily = SnProFamily, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/** Строка с `**жирным**` и `кодом`: маркеры убираются, выделение остаётся. */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

@Composable
private fun SecurityCard(fingerprint: String?) {
    InfoCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_vap_shield), contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(stringResource(R.string.upd_security_title), color = PanelColors.Ink, fontFamily = SnProFamily, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.upd_signed_by_author), color = PanelColors.Muted, fontFamily = SnProFamily, fontSize = 12.sp)
                }
            }
            Text(stringResource(R.string.upd_security_body), color = PanelColors.Muted, fontFamily = SnProFamily, fontSize = 13.sp, lineHeight = 18.sp)
            fingerprint?.let {
                Text(
                    "${stringResource(R.string.upd_signature_label)}: ${it.chunked(4).take(4).joinToString(" ")}…${it.takeLast(4)}",
                    color = PanelColors.Muted, fontFamily = SnProFamily, fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun Actions(
    state: UpdateState,
    onClose: () -> Unit,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onCheck: () -> Unit,
    onAllow: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (state) {
            UpdateState.Unavailable -> {
                PrimaryButton(stringResource(R.string.upd_open_releases), BrandOrange) { onOpen(RELEASES_PAGE_URL) }
                GhostButton(stringResource(R.string.close), onClose)
            }
            UpdateState.Idle, UpdateState.Checking, is UpdateState.Installing -> GhostButton(stringResource(R.string.close), onClose)
            is UpdateState.UpToDate -> {
                PrimaryButton(stringResource(R.string.upd_check_now), BrandOrange, onCheck)
                GhostButton(stringResource(R.string.close), onClose)
            }
            is UpdateState.Available -> {
                PrimaryButton(stringResource(R.string.upd_update_now), BrandOrange, onUpdate)
                GhostButton(stringResource(R.string.upd_later), onClose)
            }
            is UpdateState.Downloading -> {
                PrimaryButton(stringResource(R.string.close), BrandOrange, onClose)
                GhostButton(stringResource(R.string.upd_cancel_download), onCancel)
            }
            is UpdateState.Ready -> {
                PrimaryButton(stringResource(R.string.upd_install_now), StatusSuccess, onInstall)
                GhostButton(stringResource(R.string.upd_later), onClose)
            }
            is UpdateState.NeedsPermission -> {
                PrimaryButton(stringResource(R.string.upd_allow_installs), BrandOrange, onAllow)
                GhostButton(stringResource(R.string.upd_later), onClose)
            }
            is UpdateState.Failed -> {
                val retry: () -> Unit = when (state.reason) {
                    UpdateFailure.CHECK -> onCheck
                    UpdateFailure.INSTALL -> onInstall
                    else -> onUpdate
                }
                PrimaryButton(stringResource(R.string.upd_retry), BrandOrange, retry)
                state.release?.htmlUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    GhostButton(stringResource(R.string.upd_open_release)) { onOpen(url) }
                }
                GhostButton(stringResource(R.string.close), onClose)
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontFamily = SnProFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1)
    }
}

@Composable
private fun GhostButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = PanelColors.Muted, fontFamily = SnProFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1)
    }
}
