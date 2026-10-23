package com.phnem.vap.ai.whisper

import java.io.File
import okhttp3.OkHttpClient
import com.phnem.vap.ai.models.ModelFile
import com.phnem.vap.ai.models.ModelFiles

/** MIT ggerganov/whisper.cpp multilingual q5_1 models. Immutable revision and SHA-256. */
enum class WhisperModel(val fileName: String, val bytes: Long, val sha256: String, val ramMb: Int) {
    TINY("ggml-tiny-q5_1.bin", 32_152_673, "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7", 300),
    BASE("ggml-base-q5_1.bin", 59_707_625, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", 450),
    SMALL("ggml-small-q5_1.bin", 190_085_487, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", 1000);
    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/$fileName"
}

class WhisperModels(dir: File, client: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()) {
    private val files = ModelFiles(dir, client)
    val progress = files.progress
    private fun spec(model: WhisperModel) = ModelFile(model.fileName, model.url, model.bytes, model.sha256)
    fun ready(model: WhisperModel) = files.ready(spec(model))
    fun occupied(model: WhisperModel) = files.occupied(spec(model))
    fun cancel() = files.cancel()
    fun delete(model: WhisperModel) = files.delete(spec(model))
    suspend fun download(model: WhisperModel) = files.download(spec(model))
}
