package com.phnem.vap.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.phnem.vap.R
import com.phnem.vap.library.EpisodeParser
import com.phnem.vap.player.subtitles.SubtitleDocument
import com.phnem.vap.player.subtitles.SubtitleSettings
import com.phnem.vap.player.subtitles.SubtitleSource
import com.phnem.vap.player.subtitles.SubtitleStorage
import com.phnem.vap.player.subtitles.online.MovieHash
import com.phnem.vap.player.subtitles.online.OnlineDownload
import com.phnem.vap.player.subtitles.online.OnlineSubtitle
import com.phnem.vap.player.subtitles.online.OpenSubtitlesClient
import com.phnem.vap.player.subtitles.online.OpenSubtitlesFailure
import com.phnem.vap.player.subtitles.online.SubtitleSearch
import com.phnem.vap.ui.components.PanelBody
import com.phnem.vap.ui.components.PanelBusy
import com.phnem.vap.ui.components.PanelButton
import com.phnem.vap.ui.components.PanelCard
import com.phnem.vap.ui.components.PanelError
import com.phnem.vap.ui.components.PanelHint
import com.phnem.vap.ui.components.PanelRow
import com.phnem.vap.ui.components.PanelSection
import com.phnem.vap.ui.components.PanelSwitchRow
import com.phnem.vap.ui.components.PanelText
import com.phnem.vap.ui.components.PanelTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Онлайн-субтитры OpenSubtitles: ключ, поиск, предпросмотр, подключение (панель шторки субтитров). */
@Composable
fun ColumnScope.OpenSubtitlesPanel(uri: String, title: String, settings: SubtitleSettings, onAttach: (SubtitleSource) -> Unit) {
    val context = LocalContext.current
    val openUri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val client = remember { OpenSubtitlesClient(context) }
    val storage = remember { SubtitleStorage(context) }
    val episode = remember(title) { EpisodeParser.parse(title) }
    var query by remember(uri) { mutableStateOf(episode?.show ?: title.substringBeforeLast('.').replace('.', ' ').replace('_', ' ')) }
    var language by remember(uri) { mutableStateOf(settings.language.ifBlank { java.util.Locale.getDefault().language }) }
    var season by remember(uri) { mutableStateOf(episode?.season?.toString().orEmpty()) }
    var number by remember(uri) { mutableStateOf(episode?.episode?.toString().orEmpty()) }
    var useHash by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(false) }
    var signedIn by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<OnlineSubtitle>()) }
    var preview by remember { mutableStateOf<Pair<OnlineSubtitle, OnlineDownload>?>(null) }
    var previewText by remember { mutableStateOf("") }
    var remaining by remember { mutableStateOf<Int?>(null) }
    var hashUnavailable by remember { mutableStateOf(false) }
    val latestSettings by rememberUpdatedState(settings)
    val latestAttach by rememberUpdatedState(onAttach)
    val errors = mapOf("key" to stringResource(R.string.os_error_key), "session" to stringResource(R.string.os_error_session),
        "access" to stringResource(R.string.os_error_access), "quota" to stringResource(R.string.os_error_quota),
        "response" to stringResource(R.string.os_error_response), "server" to stringResource(R.string.os_error_server),
        "download" to stringResource(R.string.os_error_download), "input" to stringResource(R.string.os_error_input), "network" to stringResource(R.string.os_error_network))
    fun job(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = errors[when (e) { is OpenSubtitlesFailure -> e.reason; is IllegalArgumentException -> "input"; is org.json.JSONException -> "response"; else -> "network" }] }
            finally { busy = false }
        }
    }
    LaunchedEffect(client) {
        try { configured = client.configured(); signedIn = client.signedIn() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { error = errors["key"] }
    }
    PanelBody {
        PanelHint(stringResource(R.string.os_privacy))
        PanelRow(stringResource(if (signedIn) R.string.os_signed_in else if (configured) R.string.os_key_ready else R.string.os_not_configured),
            { account = !account }, value = stringResource(R.string.os_account))
        if (account) {
            PanelCard {
                PanelTextField(key, { key = it.take(512) }, stringResource(R.string.os_api_key), password = true)
                PanelButton(stringResource(R.string.os_save_key), { job { client.saveKey(key); key = ""; configured = true; signedIn = false } },
                    enabled = !busy && key.isNotBlank(), modifier = Modifier.fillMaxWidth())
                PanelButton(stringResource(R.string.os_get_key), { openUri.openUri("https://www.opensubtitles.com/en/consumers") }, modifier = Modifier.fillMaxWidth())
                if (configured) PanelButton(stringResource(R.string.os_remove_key), { job { client.clear(); configured = false; signedIn = false; results = emptyList() } },
                    enabled = !busy, danger = true, modifier = Modifier.fillMaxWidth())
                PanelHint(stringResource(R.string.os_optional_login))
                PanelTextField(username, { username = it.take(128) }, stringResource(R.string.os_username))
                PanelTextField(password, { password = it.take(512) }, stringResource(R.string.os_password), password = true)
                PanelButton(stringResource(R.string.os_login), { job { client.login(username, password); password = ""; signedIn = true } },
                    enabled = configured && !busy && username.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth())
                if (signedIn) PanelButton(stringResource(R.string.os_logout), { job { client.logout(); signedIn = false } }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            }
        }
        preview?.let { (offer, download) ->
            PanelCard(active = true) {
                PanelText(offer.release.ifBlank { offer.language }, bold = true)
                PanelText(previewText, size = 13)
                PanelButton(stringResource(R.string.os_attach), {
                    job {
                        val name = "OpenSubtitles #${offer.fileId} · ${offer.language} · ${offer.release}".take(250)
                        val existing = latestSettings.sources.firstOrNull { it.title == name }
                        require(existing != null || latestSettings.sources.size < 32)
                        val source = existing ?: storage.import(android.net.Uri.fromFile(download.file)).copy(title = name, language = offer.language)
                        latestAttach(source); preview = null
                    }
                }, enabled = !busy, primary = true, modifier = Modifier.fillMaxWidth())
                PanelButton(stringResource(R.string.close), { preview = null }, modifier = Modifier.fillMaxWidth())
            }
        }
        PanelSection(stringResource(R.string.os_search))
        PanelTextField(query, { query = it.take(250) }, stringResource(R.string.os_query))
        PanelTextField(language, { language = it.take(60) }, stringResource(R.string.os_languages))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelTextField(season, { season = it.take(3) }, stringResource(R.string.os_season), Modifier.weight(1f), keyboardType = KeyboardType.Number)
            PanelTextField(number, { number = it.take(4) }, stringResource(R.string.os_episode), Modifier.weight(1f), keyboardType = KeyboardType.Number)
        }
        PanelSwitchRow(stringResource(R.string.os_use_hash), useHash, { useHash = it })
        PanelButton(stringResource(R.string.os_search), {
            job {
                val hash = if (useHash) try { MovieHash.read(context, android.net.Uri.parse(uri)) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } else null
                hashUnavailable = useHash && hash == null
                results = client.search(SubtitleSearch(query, language, season.toIntOrNull(), number.toIntOrNull(), hash))
                searched = true
            }
        }, enabled = !busy && (season.isBlank() || season.toIntOrNull() != null) && (number.isBlank() || number.toIntOrNull() != null),
            primary = true, modifier = Modifier.fillMaxWidth())
        if (busy) PanelBusy()
        error?.let { PanelError(it) }
        remaining?.let { PanelText(stringResource(R.string.os_remaining, it)) }
        if (hashUnavailable) PanelHint(stringResource(R.string.os_hash_unavailable))
        if (searched && results.isEmpty()) PanelText(stringResource(R.string.os_no_matches))
        results.forEach { offer ->
            PanelCard {
                PanelText("${offer.language} · ${offer.release.ifBlank { offer.fileName }}", bold = true)
                PanelHint(listOfNotNull(if (offer.hashMatch) stringResource(R.string.os_exact_match) else null,
                    if (offer.trusted) stringResource(R.string.os_trusted) else null,
                    if (offer.translated) stringResource(R.string.os_translated) else null,
                    if (offer.hearingImpaired) "SDH" else null, offer.fps?.let { "$it FPS" }, "↓ ${offer.downloads}").joinToString(" · "))
                PanelButton(stringResource(R.string.os_download_preview), {
                    job {
                        val download = client.download(offer)
                        previewText = withContext(Dispatchers.IO) { SubtitleDocument.parse(download.file.readText()).take(12).joinToString("\n\n") { it.text } }
                        remaining = download.remaining; preview = offer to download
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
