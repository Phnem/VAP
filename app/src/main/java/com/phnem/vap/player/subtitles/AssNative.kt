package com.phnem.vap.player.subtitles

import android.graphics.Bitmap
import androidx.annotation.Keep

@Keep
data class AssFrame(val bitmap: Bitmap?, val x: Int, val y: Int)

/** A handle belongs to one render coroutine, including its destruction. */
@Keep
object AssNative {
    val available: Boolean = runCatching { System.loadLibrary("vap_ass"); true }.getOrDefault(false)
    external fun create(document: ByteArray, defaultFont: String, fontFiles: Array<String> = emptyArray()): Long
    external fun render(handle: Long, timeMs: Long, width: Int, height: Int): AssFrame?
    external fun release(handle: Long)
}
