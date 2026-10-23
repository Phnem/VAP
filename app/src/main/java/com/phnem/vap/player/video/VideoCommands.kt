package com.phnem.vap.player.video

import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object VideoCommands {
    val INFO = SessionCommand("com.phnem.vap.VIDEO_INFO", Bundle.EMPTY)
    val RETRY = SessionCommand("com.phnem.vap.VIDEO_RETRY", Bundle.EMPTY)
}

suspend fun MediaController.videoInfo(): Bundle = suspendCancellableCoroutine { cont ->
    val future = sendCustomCommand(VideoCommands.INFO, Bundle.EMPTY)
    future.addListener({
        if (cont.isActive) try {
            val result = future.get()
            if (result.resultCode == SessionResult.RESULT_SUCCESS) cont.resume(result.extras)
            else cont.resume(Bundle.EMPTY)
        } catch (error: Exception) { cont.resumeWithException(error) }
    }, { task -> task.run() })
    cont.invokeOnCancellation { future.cancel(false) }
}
