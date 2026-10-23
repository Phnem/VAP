package com.phnem.vap.player.audio

import android.os.Bundle
import androidx.media3.session.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object AudioCommands {
    val INFO = SessionCommand("com.phnem.vap.AUDIO_INFO", Bundle.EMPTY)
    val ROUTE = SessionCommand("com.phnem.vap.AUDIO_ROUTE", Bundle.EMPTY)
}
suspend fun MediaController.audioInfo(): Bundle = suspendCancellableCoroutine { cont ->
    val future = sendCustomCommand(AudioCommands.INFO, Bundle.EMPTY)
    future.addListener({ if (cont.isActive) try {
        val result = future.get()
        if (result.resultCode == SessionResult.RESULT_SUCCESS) cont.resume(result.extras) else cont.resume(Bundle.EMPTY)
    } catch (error: Exception) { cont.resumeWithException(error) } }, { it.run() })
    cont.invokeOnCancellation { future.cancel(false) }
}
