package com.phnem.vap.player.video

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.view.SurfaceView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

data class VideoToolState(val busy: Boolean = false, val progress: Int? = null, val output: File? = null,
    val error: String? = null, val saved: Boolean = false)

class VideoToolsModel(app: Application) : AndroidViewModel(app) {
    private val mutableState = MutableStateFlow(VideoToolState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    fun cancel() { job?.cancel() }

    private fun run(extension: String, action: suspend (File) -> Unit) {
        if (state.value.busy) return
        state.value.output?.delete()
        mutableState.value = VideoToolState(busy = true)
        job = viewModelScope.launch {
            var output: File? = null
            var complete = false
            try {
                val folder = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
                val created = File.createTempFile("VAP-", ".$extension", folder)
                output = created
                action(created)
                coroutineContext.ensureActive()
                check(created.length() > 0) { "Empty export" }
                complete = true
                mutableState.value = VideoToolState(output = created)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = VideoToolState(error = error.message?.take(240) ?: error.javaClass.simpleName) }
            finally {
                if (!complete) output?.delete()
                if (mutableState.value.busy) mutableState.value = mutableState.value.copy(busy = false, progress = null)
            }
        }
    }
    fun capture(surface: SurfaceView, width: Int, height: Int) = run("png") { output ->
        val bitmap = MediaExports.frame(surface, width, height)
        try { withContext(Dispatchers.IO) { output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } }
        finally { bitmap.recycle() }
    }
    fun clip(uri: Uri, range: ExportRange, picture: PictureSettings) = run("mp4") { output ->
        MediaExports.clip(getApplication(), uri, range, picture, output) { percent -> mutableState.value = mutableState.value.copy(progress = percent) }
    }
    fun gif(uri: Uri, range: ExportRange, picture: PictureSettings) = run("gif") { output ->
        MediaExports.gif(getApplication(), uri, range, picture, output) { percent -> mutableState.value = mutableState.value.copy(progress = percent) }
    }
    fun save(uri: Uri) {
        val output = state.value.output ?: return
        if (state.value.busy) return
        mutableState.value = state.value.copy(busy = true, error = null)
        job = viewModelScope.launch {
            var complete = false
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "w").use { target ->
                        checkNotNull(target) { "Cannot open destination" }
                        output.inputStream().use { source ->
                            val bytes = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = source.read(bytes)
                                if (count < 0) break
                                target.write(bytes, 0, count)
                            }
                        }
                    }
                }
                output.delete()
                complete = true
                mutableState.value = VideoToolState(saved = true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = mutableState.value.copy(error = error.message?.take(240), busy = false) }
            finally {
                if (!complete) withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    // CreateDocument supplied a newly created destination; do not leave it half written.
                    runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) }
                }
                if (mutableState.value.busy) mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }
    override fun onCleared() { state.value.output?.delete(); super.onCleared() }
}
