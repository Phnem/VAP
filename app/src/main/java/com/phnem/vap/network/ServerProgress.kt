package com.phnem.vap.network

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Serial reports; network failures never stop local playback or retain an unbounded queue. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ServerProgress(context: Context, private val player: Player, private val client: ServerClient = ServerClient(), private val enabled: () -> Boolean = { true }) : Player.Listener {
    private val store = NetworkConnectionStore(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Snapshot(val uri: String, val ref: ServerPlaybackRef, val position: Long, val duration: Long, val paused: Boolean)
    private data class Report(val value: Snapshot, val stopped: Boolean, val started: Boolean)
    private val reports = Channel<Report>(32)
    private var previous: Snapshot? = null
    private var lastSent = 0L
    private var lastPosition = 0L
    private var hasStarted = false
    init {
        player.addListener(this)
        scope.launch {
            for (report in reports) {
                try {
                    val connection = store.list().firstOrNull { it.id == report.value.ref.connectionId && it.isServer } ?: continue
                    val uri = runCatching { NetworkUrls.parse(report.value.uri) }.getOrNull() ?: continue
                    if (!connection.contains(uri)) continue
                    client.progress(connection, report.value.ref, report.value.position, report.value.duration, report.value.paused, report.stopped, report.started)
                } catch (e: CancellationException) { throw e } catch (_: Exception) { /* next update retries; no passwords or response bodies in logs */ }
            }
        }
    }
    fun sample(force: Boolean = false) {
        if (!enabled()) { previous = null; hasStarted = false; return }
        val item = player.currentMediaItem
        val ref = ServerPlaybackRef.parse(item?.mediaMetadata?.extras?.getString(ServerPlaybackRef.EXTRA))
        if (ref != previous?.ref) {
            previous?.takeIf { hasStarted }?.let { reports.trySend(Report(it, stopped = true, started = false)) }
            previous = null; hasStarted = false; lastSent = 0; lastPosition = 0
        }
        if (ref == null || item?.localConfiguration == null) return
        val snapshot = Snapshot(item.localConfiguration!!.uri.toString(), ref, player.currentPosition.coerceAtLeast(0), player.duration.coerceAtLeast(0), !player.playWhenReady)
        previous = snapshot
        if (player.playbackState == Player.STATE_ENDED) {
            if (hasStarted) reports.trySend(Report(snapshot, stopped = true, started = false))
            previous = null; hasStarted = false; return
        }
        if (player.playbackState != Player.STATE_READY || snapshot.duration <= 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (force || !hasStarted || now - lastSent >= 10_000 || kotlin.math.abs(snapshot.position - lastPosition) > 15_000) {
            if (reports.trySend(Report(snapshot, stopped = false, started = !hasStarted)).isSuccess) {
                hasStarted = true; lastSent = now; lastPosition = snapshot.position
            }
        }
    }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { sample(true) }
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        val oldRef = ServerPlaybackRef.parse(oldPosition.mediaItem?.mediaMetadata?.extras?.getString(ServerPlaybackRef.EXTRA))
        val newRef = ServerPlaybackRef.parse(newPosition.mediaItem?.mediaMetadata?.extras?.getString(ServerPlaybackRef.EXTRA))
        if (oldRef != newRef && oldRef == previous?.ref) {
            previous?.takeIf { hasStarted }?.let { reports.trySend(Report(it.copy(position = oldPosition.positionMs.coerceAtLeast(0)), true, false)) }
            previous = null; hasStarted = false
        }
    }
    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_POSITION_DISCONTINUITY)) sample(true)
    }
    fun release() {
        player.removeListener(this)
        previous?.takeIf { hasStarted }?.let { reports.trySend(Report(it.copy(position = player.currentPosition.coerceAtLeast(0)), true, false)) }
        reports.close()
        scope.launch { delay(9000); scope.cancel() }
    }
}
