package com.phnem.vap.player

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.phnem.vap.data.VapData
import com.phnem.vap.player.model.PlayableItem
import com.phnem.vap.player.pip.PipActionsController
import com.phnem.vap.player.pip.PipHostActivity
import com.phnem.vap.player.pip.PipPlaybackCommands
import com.phnem.vap.player.pip.pipWindowWasClosed
import com.phnem.vap.player.ui.PlayerScreen
import com.phnem.vap.ui.theme.VapTheme

/** Экран контроллера: Home оставляет звук в сервисе, Back ставит на паузу. */
class PlayerActivity : ComponentActivity(), PipHostActivity {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.phnem.vap.ui.onboarding.AppLocale.wrap(newBase, com.phnem.vap.ui.onboarding.AppPrefs(newBase).language))
    }

    private var player by mutableStateOf<androidx.media3.session.MediaController?>(null)
    private var connection: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null
    private val pipState = mutableStateOf(false)
    private val pipActions = PipActionsController(this)
    private var wasInPip = false
    private var pendingOpen: Intent? = null

    override fun updatePipCommands(commands: PipPlaybackCommands?) = pipActions.setCommands(commands)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pipActions.register()
        pendingOpen = if (savedInstanceState == null) intent else Intent().setAction(PlaybackService.ACTION_RESUME)
        val future = PlaybackConnection.connect(this)
        connection = future
        future.addListener({
            if (isDestroyed) return@addListener
            runCatching {
                player = future.get()
                pendingOpen?.let { open(it) }
                pendingOpen = null
            }.onFailure {
                android.util.Log.e("VapPlayback", "Cannot connect to playback", it)
                finish()
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
        setContent {
            VapTheme(darkTheme = true) {
                BackHandler { closePlayer() }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    val connected = player
                    if (connected != null) PlayerScreen(
                        exoPlayer = connected,
                        isInPip = pipState.value,
                        onEnterPip = { pipActions.enterPip() },
                        onBack = { closePlayer() },
                        onLibrary = {
                            startActivity(Intent(this@PlayerActivity, com.phnem.vap.MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (player == null) pendingOpen = intent else open(intent)
    }

    private fun open(request: Intent) {
        val connected = player ?: return
        if (request.action == PlaybackService.ACTION_RESUME) {
            if (connected.mediaItemCount == 0) finish()
            else if (connected.playbackState == androidx.media3.common.Player.STATE_IDLE) connected.prepare()
            return
        }
        val uris = request.getStringArrayListExtra(EXTRA_URIS)
        val titles = request.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        val types = request.getStringArrayListExtra(EXTRA_TYPES).orEmpty()
        val trusted = request.getStringExtra(EXTRA_REQUEST_TOKEN) == requestToken
        val refs = if (trusted) request.getStringArrayListExtra(EXTRA_REFS).orEmpty() else emptyList()
        val starts = if (trusted) request.getLongArrayExtra(EXTRA_POSITIONS) else null
        val items = if (!uris.isNullOrEmpty()) uris.mapIndexed { index, uri ->
            PlayableItem(uri, titles.getOrNull(index).orEmpty(), types.getOrNull(index)?.takeIf { it in NETWORK_MIMES }, com.phnem.vap.network.ServerPlaybackRef.parse(refs.getOrNull(index)), starts?.getOrNull(index)?.coerceAtLeast(0) ?: 0)
        } else request.data?.let { listOf(PlayableItem(it.toString(), it.lastPathSegment.orEmpty(), request.type?.takeIf { type -> type in NETWORK_MIMES })) }.orEmpty()
        if (items.isEmpty()) { finish(); return }
        val index = request.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(items.indices)
        val position = if (items[index].server != null) items[index].startPositionMs else VapData.history(this).resumePosition(items[index].uri)
        connected.setMediaItems(items.map { it.toMediaItem() }, index, position)
        connected.prepare()
        connected.play()
    }

    private fun closePlayer() {
        player?.pause()
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (wasInPip && !isInPictureInPictureMode && !isChangingConfigurations && !isFinishing) closePlayer()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipState.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) wasInPip = true
        if (pipWindowWasClosed(isInPictureInPictureMode)) closePlayer()
        else if (!isInPictureInPictureMode) wasInPip = false
    }

    override fun onDestroy() {
        pipActions.unregister()
        connection?.let { androidx.media3.session.MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_TYPES = "types"
        private const val EXTRA_REFS = "server_refs"
        private const val EXTRA_POSITIONS = "positions"
        private const val EXTRA_REQUEST_TOKEN = "request_token"
        private val requestToken = java.util.UUID.randomUUID().toString()
        private const val EXTRA_START_INDEX = "start_index"
        private val NETWORK_MIMES = setOf("application/x-mpegURL", "application/x-mpegurl", "application/vnd.apple.mpegurl", "application/dash+xml", "video/mp4", "video/webm", "video/x-matroska", "video/mp2t")

        fun intent(context: Context, items: List<PlayableItem>, startIndex: Int): Intent =
            Intent(context, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putStringArrayListExtra(EXTRA_URIS, ArrayList(items.map { it.uri }))
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(items.map { it.title }))
                .putStringArrayListExtra(EXTRA_TYPES, ArrayList(items.map { it.mimeType.orEmpty() }))
                .putStringArrayListExtra(EXTRA_REFS, ArrayList(items.map { it.server?.json().orEmpty() }))
                .putExtra(EXTRA_POSITIONS, items.map { it.startPositionMs }.toLongArray())
                .putExtra(EXTRA_REQUEST_TOKEN, requestToken)
                .putExtra(EXTRA_START_INDEX, startIndex)
    }
}
