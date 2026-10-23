package com.phnem.vap.network

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.phnem.vap.R
import com.phnem.vap.player.model.PlayableItem
import com.phnem.vap.ui.components.PanelBody
import com.phnem.vap.ui.components.PanelBusy
import com.phnem.vap.ui.components.PanelButton
import com.phnem.vap.ui.components.PanelCard
import com.phnem.vap.ui.components.PanelError
import com.phnem.vap.ui.components.PanelFooter
import com.phnem.vap.ui.components.PanelHeader
import com.phnem.vap.ui.components.PanelHint
import com.phnem.vap.ui.components.PanelPill
import com.phnem.vap.ui.components.PanelPillRow
import com.phnem.vap.ui.components.PanelRow
import com.phnem.vap.ui.components.PanelSection
import com.phnem.vap.ui.components.PanelText
import com.phnem.vap.ui.components.PanelTextField
import com.phnem.vap.ui.components.VapSheet
import com.phnem.vap.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Сетевые источники: подключения, обзор папок и серверов, буферизация. Шторка Collection в теме приложения. */
@Composable
fun NetworkSourcesDialog(onPlay: (List<PlayableItem>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { NetworkConnectionStore(context) }
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf(emptyList<NetworkConnection>()) }
    var editor by remember { mutableStateOf(false) }
    var editedId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var hostKey by remember { mutableStateOf("") }
    var streamType by remember { mutableStateOf("auto") }
    var token by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(NetworkKind.URL) }
    var browser by remember { mutableStateOf<NetworkConnection?>(null) }
    var path by remember { mutableStateOf("") }
    var entryPage by remember(path, browser?.id) { mutableIntStateOf(0) }
    val contentScroll = rememberScrollState()
    var entries by remember { mutableStateOf(emptyList<NetworkEntry>()) }
    var trail by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var folderLabel by remember { mutableStateOf("") }
    var discovered by remember { mutableStateOf(emptyList<UpnpProtocol.Server>()) }
    var scanned by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var buffer by remember { mutableStateOf(NetworkBuffer.read(context)) }
    val invalid = stringResource(R.string.network_invalid)
    val failed = stringResource(R.string.network_failed)
    val serverClient = remember { ServerClient() }
    val isServer = kind in setOf(NetworkKind.JELLYFIN, NetworkKind.EMBY, NetworkKind.PLEX)
    fun task(action: suspend () -> Unit) {
        if (busy) return
        busy = true; failure = null
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { failure = invalid }
            catch (_: Exception) { failure = failed }
            finally { busy = false }
        }
    }
    fun browse(connection: NetworkConnection, directory: String, label: String = connection.name, back: Boolean = false) = task {
        val found = when(connection.kind) {
            NetworkKind.WEBDAV -> WebDavBrowser.list(connection, directory)
            NetworkKind.DLNA -> DlnaBrowser.list(connection, directory)
            NetworkKind.JELLYFIN, NetworkKind.EMBY, NetworkKind.PLEX -> serverClient.browse(connection, directory)
            else -> RemoteFiles.list(connection, directory)
        }
        trail = if (back) trail.dropLast(1) else if (browser?.id == connection.id) trail + (path to folderLabel) else emptyList()
        browser = connection; path = when(connection.kind) { NetworkKind.WEBDAV -> NetworkUrls.directory(directory).toString(); NetworkKind.DLNA, NetworkKind.JELLYFIN, NetworkKind.EMBY, NetworkKind.PLEX -> directory; else -> RemoteAddress.parse(directory).directory().url }
        folderLabel = label
        entries = found
    }
    fun edit(connection: NetworkConnection?) {
        editedId = connection?.id; name = connection?.name.orEmpty(); url = connection?.url.orEmpty()
        username = connection?.username.orEmpty(); password = connection?.password.orEmpty(); kind = connection?.kind ?: NetworkKind.URL
        hostKey = connection?.hostKey.orEmpty()
        streamType = connection?.streamType ?: "auto"
        token = connection?.token.orEmpty(); userId = connection?.userId.orEmpty()
        editor = true; failure = null
    }
    LaunchedEffect(store) { task { connections = store.list() } }
    VapSheet(onDismiss, isDark = isAppInDarkTheme(), immersive = false) { close ->
        BackHandler(editor || browser != null) {
            if (editor) { editor = false; password = "" }
            else if (!busy) { val current = browser; if (current != null && trail.isNotEmpty()) trail.last().let { (parent, label) -> browse(current, parent, label, true) } else browser = null }
        }
        PanelHeader(browser?.name ?: stringResource(R.string.network_sources), onClose = close)
        if (busy) PanelBusy(Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp))
        failure?.let { PanelError(it, Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp)) }
        PanelBody(scroll = contentScroll) {
            val current = browser
            when {
                editor -> {
                    PanelPillRow {
                        NetworkKind.entries.forEach { value ->
                            PanelPill(when(value) { NetworkKind.URL -> "HTTP / HLS / DASH"; NetworkKind.JELLYFIN -> "Jellyfin"; NetworkKind.EMBY -> "Emby"; NetworkKind.PLEX -> "Plex"; else -> value.name },
                                kind == value, { kind = value })
                        }
                    }
                    PanelTextField(name, { name = it.take(150) }, stringResource(R.string.network_name))
                    PanelTextField(url, { url = it.take(8192) }, stringResource(when { kind == NetworkKind.URL -> R.string.network_url; kind == NetworkKind.DLNA -> R.string.network_device_url; isServer -> R.string.server_url; else -> R.string.network_root }),
                        keyboardType = KeyboardType.Uri)
                    if (kind == NetworkKind.URL) PanelPillRow {
                        listOf("auto" to stringResource(R.string.audio_automatic), "hls" to "HLS", "dash" to "DASH").forEach { (value, label) ->
                            PanelPill(label, streamType == value, { streamType = value })
                        }
                    }
                    if (kind != NetworkKind.DLNA && kind != NetworkKind.PLEX) {
                        PanelTextField(username, { username = it.take(512) }, stringResource(R.string.network_user))
                        PanelTextField(password, { password = it.take(2048) }, stringResource(R.string.os_password), password = true)
                        PanelHint(stringResource(R.string.network_auth_scope))
                    }
                    if (isServer) {
                        PanelTextField(token, { token = it.take(8192) }, stringResource(R.string.server_token), password = true)
                        if (kind != NetworkKind.PLEX) PanelTextField(userId, { userId = it.take(128) }, stringResource(R.string.server_user_id))
                        PanelHint(stringResource(if (kind == NetworkKind.PLEX) R.string.server_plex_auth else R.string.server_auth_note))
                    }
                    if (kind == NetworkKind.SFTP) {
                        PanelTextField(hostKey, { hostKey = it.take(80) }, stringResource(R.string.network_host_key))
                        PanelHint(stringResource(R.string.network_host_key_note))
                    }
                    if (url.trim().startsWith("http://", true) || kind == NetworkKind.FTP) PanelError(stringResource(R.string.network_cleartext))
                    PanelButton(stringResource(R.string.network_save), {
                        task {
                            val cleanUrl = if (kind !in setOf(NetworkKind.SMB, NetworkKind.FTP, NetworkKind.SFTP)) NetworkUrls.parse(url).toString() else RemoteAddress.parse(url).url
                            val connection = NetworkConnection(editedId ?: java.util.UUID.randomUUID().toString(), name.trim().ifBlank { java.net.URI(cleanUrl).host }, kind, cleanUrl,
                                if (kind == NetworkKind.DLNA || kind == NetworkKind.PLEX) "" else username, if (kind == NetworkKind.DLNA || kind == NetworkKind.PLEX) "" else password, hostKey.trim(), streamType, if (isServer) token.trim() else "", if (isServer) userId.trim() else "")
                            val configured = if (!isServer) connection else if (kind != NetworkKind.PLEX && (token.isBlank() || password.isNotEmpty())) serverClient.login(connection, username, password) else serverClient.validate(connection)
                            store.save(configured); connections = store.list(); password = ""; token = ""; editor = false
                        }
                    }, enabled = !busy && url.isNotBlank(), primary = true, modifier = Modifier.fillMaxWidth())
                    PanelButton(stringResource(R.string.network_back), { editor = false; password = "" }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                }
                current != null -> {
                    PanelHint(if (current.kind == NetworkKind.DLNA || current.isServer) folderLabel else java.net.URI(path).path)
                    PanelRow(stringResource(R.string.network_up), {
                        if (trail.isEmpty()) { browser = null; entries = emptyList() }
                        else trail.last().let { (parent, label) -> browse(current, parent, label, back = true) }
                    }, enabled = !busy)
                    if (entries.isEmpty() && !busy) PanelText(stringResource(R.string.network_empty))
                    if (entries.size >= 5000 || current.isServer || current.kind == NetworkKind.DLNA) PanelHint(stringResource(R.string.network_listing_limit))
                    entries.drop(entryPage * 100).take(100).forEach { entry ->
                        PanelRow((if (entry.folder) "▸ " else "▶ ") + entry.name, {
                            if (entry.folder) browse(current, entry.url, entry.name)
                            else if (current.isServer) task { onPlay(listOf(serverClient.play(current, entry))) }
                            else onPlay(listOf(PlayableItem(entry.url, entry.name, entry.mimeType)))
                        }, subtitle = entry.size?.let { "${it / 1024} KiB" }, enabled = !busy)
                    }
                    if (entries.size > 100) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        PanelButton(stringResource(R.string.server_previous), { entryPage--; scope.launch { contentScroll.scrollTo(0) } }, enabled = entryPage > 0)
                        PanelText("${entryPage * 100 + 1}–${minOf((entryPage + 1) * 100, entries.size)} / ${entries.size}", Modifier.weight(1f))
                        PanelButton(stringResource(R.string.server_next), { entryPage++; scope.launch { contentScroll.scrollTo(0) } }, enabled = (entryPage + 1) * 100 < entries.size)
                    }
                }
                else -> {
                    PanelButton(stringResource(R.string.network_add), { edit(null) }, enabled = !busy, primary = true, modifier = Modifier.fillMaxWidth())
                    PanelRow(stringResource(R.string.network_find_dlna), { task { discovered = DlnaBrowser.discover(context); scanned = true } }, enabled = !busy)
                    if (scanned && discovered.isEmpty()) PanelText(stringResource(R.string.network_no_dlna))
                    discovered.forEach { server ->
                        PanelRow("+ ${server.name}", {
                            task {
                                if (connections.none { it.kind == NetworkKind.DLNA && it.url == server.location })
                                    store.save(NetworkConnection(name = server.name, kind = NetworkKind.DLNA, url = server.location))
                                connections = store.list(); discovered = discovered.filter { it.location != server.location }
                            }
                        }, enabled = !busy)
                    }
                    PanelSection(stringResource(R.string.network_buffer))
                    PanelPillRow {
                        NetworkBuffer.entries.forEach { value ->
                            PanelPill(stringResource(when(value) { NetworkBuffer.LOW -> R.string.network_buffer_low; NetworkBuffer.NORMAL -> R.string.network_buffer_normal; NetworkBuffer.LARGE -> R.string.network_buffer_large }),
                                buffer == value, { NetworkBuffer.save(context, value); buffer = value })
                        }
                    }
                    PanelHint(stringResource(R.string.network_buffer_note))
                    connections.forEach { connection ->
                        PanelCard {
                            PanelText(connection.name, bold = true)
                            PanelHint("${connection.kind} · ${java.net.URI(connection.url).host}")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PanelButton(stringResource(R.string.action_open), {
                                    if (connection.kind != NetworkKind.URL) browse(connection, connection.rootUrl)
                                    else onPlay(listOf(PlayableItem(connection.url, connection.name, connection.mimeType)))
                                }, Modifier.weight(1f), enabled = !busy, primary = true)
                                PanelButton(stringResource(R.string.network_edit), { edit(connection) }, Modifier.weight(1f), enabled = !busy)
                                PanelButton(stringResource(R.string.network_delete), { task { store.remove(connection.id); connections = store.list() } },
                                    Modifier.weight(1f), enabled = !busy, danger = true)
                            }
                        }
                    }
                }
            }
        }
    }
}
