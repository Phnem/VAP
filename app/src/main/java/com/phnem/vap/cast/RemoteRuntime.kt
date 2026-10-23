package com.phnem.vap.cast

import android.content.Context
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.phnem.vap.cast.cast.createCastAdapter
import com.phnem.vap.cast.dlna.DlnaAdapter
import com.phnem.vap.cast.proxy.*
import com.phnem.vap.network.*
import com.phnem.vap.player.PlaybackConnection
import com.phnem.vap.player.settings.PlayerPreferences
import com.phnem.vap.player.subtitles.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

/** Application-owned remote session. Activity recreation does not close the transport or proxy. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RemoteRuntime internal constructor(private val context: Context,
    addressFor: (String?) -> java.net.Inet4Address? = LanNetwork::addressFor,
    lanAvailable: () -> Boolean = LanNetwork::hasLan,
    startService: (String) -> Unit = { RemotePlaybackService.start(context, it) },
    stopService: () -> Unit = { RemotePlaybackService.stop(context) }) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val plainHttp = okhttp3.OkHttpClient.Builder().followRedirects(false).connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val clients = mutableMapOf<String, okhttp3.OkHttpClient>()
    private fun clientFor(id: String?): okhttp3.OkHttpClient = synchronized(clients) {
        clients.getOrPut(id.orEmpty()) { NetworkHttp.playback(context, id) }
    }
    private val proxy = CastProxyServer(plainHttp, ::clientFor, LocalCastFiles(context)::open)
    val dlna = DlnaAdapter(context, plainHttp)
    val manager = RemotePlaybackManager(context, listOfNotNull(createCastAdapter(context), dlna), RemoteMediaPreparer(proxy, plainHttp, addressFor, ::clientFor), scope, lanAvailable, startService, stopService)
    val player = RemoteSessionPlayer(manager)
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var report: ServerProgress? = null
    private var reportJob: Job? = null
    private var subtitleFile: File? = null
    private var sentId: String? = null
    val active: Boolean get() = manager.media.value != null
    private val guard = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (active && playWhenReady) controller?.pause() }
    }


    suspend fun cast(device: RemoteDevice, sendSubtitles: Boolean) {
        check(!active)
        val future = PlaybackConnection.connect(context).also { controllerFuture = it }
        try {
            val local = withContext(Dispatchers.IO) { future.get(15, TimeUnit.SECONDS) }.also { controller = it }
            val item = local.currentMediaItem ?: error("No video")
            val uri = item.localConfiguration?.uri ?: error("No source")
            val ref = ServerPlaybackRef.parse(item.mediaMetadata.extras?.getString(ServerPlaybackRef.EXTRA))
            val subs = mutableListOf<RemoteSubtitle>()
            if (sendSubtitles) {
                val settings = PlayerPreferences(context).fileSettings(item.mediaId).first().subtitles
                val source = settings.sources.firstOrNull { it.id == settings.selection }
                if (source != null) {
                    val cues = SubtitleStorage(context).read(source.id)
                    val file = withContext(Dispatchers.IO) {
                        File(context.cacheDir, "cast-subtitles.srt").apply { writeText(SubtitleDocument.export(SubtitleDocument.adjusted(cues, settings.delayMs, settings.scale))) }
                    }.also { subtitleFile = it }
                    subs += RemoteSubtitle(file.absolutePath, source.language, source.title, "application/x-subrip", true, true)
                }
            }
            val mime = (item.localConfiguration?.mimeType ?: if (uri.scheme == "content") context.contentResolver.getType(uri) else null)?.takeIf { it.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) }
            val remote = RemoteMedia(item.mediaId, item.mediaMetadata.title?.toString().orEmpty(), null, null, uri.toString(), mime,
                emptyMap(), subtitles = subs, durationMs = local.duration.takeIf { it > 0 }, startPositionMs = local.currentPosition.coerceAtLeast(0),
                forceProxy = uri.scheme !in setOf("http", "https") || ref != null || NetworkConnectionStore(context).list().any { c -> runCatching { c.contains(NetworkUrls.parse(uri.toString())) }.getOrDefault(false) },
                connectionId = ref?.connectionId, item = item)
            sentId = item.mediaId
            manager.cast(device, remote)
            local.pause(); local.addListener(guard)
            report = ServerProgress(context, player)
            reportJob = scope.launch { while (isActive) { report?.sample(); delay(1000) } }
        } catch (e: Exception) {
            runCatching { manager.disconnect() }; closeLocal(); throw e
        }
    }
    suspend fun returnToPhone(resume: Boolean = manager.playback.value.status == RemoteStatus.PLAYING) {
        reportJob?.cancel(); reportJob = null
        report?.release(); report = null
        val position = manager.disconnect()
        val local = controller
        local?.removeListener(guard)
        if (local != null && local.currentMediaItem?.mediaId == sentId) {
            local.seekTo(position.coerceAtLeast(0)); local.prepare(); local.playWhenReady = resume
        }
        closeLocal()
    }
    suspend fun close() { returnToPhone(false); manager.stopDiscovery(); proxy.closeAll(); player.release(); scope.cancel() }
    private fun closeLocal() {
        controller?.removeListener(guard); controller = null
        controllerFuture?.let(MediaController::releaseFuture); controllerFuture = null
        subtitleFile?.delete(); subtitleFile = null; sentId = null
        synchronized(clients) { clients.clear() }
    }
    companion object {
        @Volatile private var instance: RemoteRuntime? = null
        fun get(context: Context): RemoteRuntime = instance ?: synchronized(this) { instance ?: RemoteRuntime(context.applicationContext).also { instance = it } }
        fun casting(): Boolean = instance?.active == true
    }
}
