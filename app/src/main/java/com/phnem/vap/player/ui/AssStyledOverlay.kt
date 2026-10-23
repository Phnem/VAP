package com.phnem.vap.player.ui

import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import com.phnem.vap.player.subtitles.*
import kotlinx.coroutines.*
import java.io.File

/** libass evaluates layout/animation off the UI thread. Published bitmaps are never mutated. */
@Composable
fun AssStyledOverlay(player: Player, source: SubtitleSource, settings: SubtitleSettings,
    modifier: Modifier, onFailure: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(settings)
    val fail by rememberUpdatedState(onFailure)
    var size by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember(source.id) { mutableStateOf<AssFrame?>(null) }
    var raster by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(player, source.id, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.Default) {
                var handle = 0L
                try {
                    val document = SubtitleStorage(context).readAss(source.id)
                    val font = withContext(Dispatchers.IO) {
                        val directory = File("/system/fonts")
                        listOf("Roboto-Regular.ttf", "RobotoVariable.ttf", "NotoSans-Regular.ttf").map { File(directory, it) }
                            .firstOrNull { it.isFile } ?: directory.listFiles().orEmpty().first { it.extension == "ttf" }
                    }
                    handle = AssNative.create(document, font.absolutePath, SubtitleStorage(context).fonts(source.id))
                    check(handle != 0L) { "Cannot initialize ASS renderer" }
                    while (isActive) {
                        val target = size
                        if (target.width > 0 && target.height > 0) {
                            val factor = minOf(1.0, 1920.0 / target.width, 1080.0 / target.height)
                            val w = (target.width * factor).toInt().coerceAtLeast(1)
                            val h = (target.height * factor).toInt().coerceAtLeast(1)
                            val time = withContext(Dispatchers.Main.immediate) { ((player.currentPosition - latest.delayMs) / latest.scale).toLong() }
                            val next = AssNative.render(handle, time, w, h)
                            if (next != null) withContext(Dispatchers.Main.immediate) { frame = next; raster = IntSize(w, h) }
                        }
                        delay(33)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    android.util.Log.w("VapSubtitles", "ASS renderer failed", e)
                    withContext(Dispatchers.Main.immediate) { fail() }
                } finally { if (handle != 0L) AssNative.release(handle) }
            }
        }
    }
    AndroidView(modifier = modifier.onSizeChanged { size = it }, factory = { AssCanvasView(it) }, update = { view ->
        view.frame = frame; view.raster = raster; view.invalidate()
    })
}

private class AssCanvasView(context: android.content.Context) : View(context) {
    var frame: AssFrame? = null
    var raster = IntSize.Zero
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = frame ?: return
        val bitmap = frame.bitmap ?: return
        if (raster.width < 1 || raster.height < 1) return
        canvas.save()
        canvas.scale(width.toFloat() / raster.width, height.toFloat() / raster.height)
        canvas.drawBitmap(bitmap, frame.x.toFloat(), frame.y.toFloat(), null)
        canvas.restore()
    }
}
