package com.phnem.vap.player.model

/** Одно видео в очереди плеера. [uri] — `content://` из SAF или сетевой адрес. */
data class PlayableItem(
    val uri: String,
    val title: String,
    val mimeType: String? = null,
    val server: com.phnem.vap.network.ServerPlaybackRef? = null,
    val startPositionMs: Long = 0,
)
