package com.phnem.vap.ai.models

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

data class ModelFile(val fileName: String, val url: String, val bytes: Long, val sha256: String) {
    init {
        require(fileName.matches(Regex("[a-zA-Z0-9_-][a-zA-Z0-9_.-]{0,99}")) && ".." !in fileName)
        require(url.startsWith("https://") && bytes in 1..3_000_000_000L && sha256.matches(Regex("[0-9a-f]{64}")))
    }
}

data class ModelTransfer(val file: ModelFile? = null, val received: Long = 0, val verifying: Boolean = false)

/** Shared bounded model-file transfer. AiRuntime serializes jobs; cancelled transfers retain partial files. */
class ModelFiles(private val dir: File, private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()) {
    private val mutable = MutableStateFlow(ModelTransfer())
    val progress = mutable.asStateFlow()
    @Volatile private var call: Call? = null
    init { dir.mkdirs() }
    fun ready(model: ModelFile): File? = File(dir, model.fileName).takeIf { it.isFile && it.length() == model.bytes }
    fun occupied(model: ModelFile): Long = File(dir, model.fileName).length() + File(dir, model.fileName + ".part").length()
    fun cancel() { call?.cancel() }
    fun delete(model: ModelFile) {
        check(mutable.value.file == null) { "A model operation is running" }
        File(dir, model.fileName).delete(); File(dir, model.fileName + ".part").delete()
    }
    suspend fun download(model: ModelFile, onProgress: (Long) -> Unit = {}): File = withContext(Dispatchers.IO) {
        ready(model)?.let { return@withContext it }
        val part = File(dir, model.fileName + ".part")
        if (part.length() > model.bytes) part.delete()
        val offset = part.length()
        require(dir.usableSpace >= model.bytes - offset + 100L * 1024 * 1024) { "Not enough free storage" }
        mutable.value = ModelTransfer(model, offset)
        onProgress(offset)
        try {
            if (offset < model.bytes) {
                val request = Request.Builder().url(model.url).header("Accept-Encoding", "identity").apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
                val transfer = client.newCall(request); call = transfer
                coroutineContext.ensureActive()
                transfer.execute().use { response ->
                    require(response.isSuccessful) { "Model download HTTP ${response.code}" }
                    val append = response.code == 206
                    if (append) require(response.header("Content-Range") == "bytes $offset-${model.bytes - 1}/${model.bytes}") { "Invalid model byte range" }
                    val body = response.body ?: error("Empty model response")
                    val expected = model.bytes - if (append) offset else 0
                    require(body.contentLength() == -1L || body.contentLength() == expected) { "Invalid model length" }
                    RandomAccessFile(part, "rw").use { output ->
                        if (!append) output.setLength(0)
                        output.seek(output.length())
                        body.byteStream().use { input ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer); if (n < 0) break
                                require(output.filePointer + n <= model.bytes) { "Model exceeds declared size" }
                                output.write(buffer, 0, n)
                                mutable.value = ModelTransfer(model, output.filePointer)
                                onProgress(output.filePointer)
                            }
                        }
                        output.fd.sync()
                    }
                }
            }
            coroutineContext.ensureActive()
            mutable.value = ModelTransfer(model, part.length(), true)
            require(part.length() == model.bytes) { "Incomplete model download; retry to resume" }
            val digest = MessageDigest.getInstance("SHA-256")
            part.inputStream().buffered().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) { coroutineContext.ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != model.sha256) { part.delete(); error("Model SHA-256 mismatch") }
            val target = File(dir, model.fileName)
            check(part.renameTo(target)) { "Cannot save model" }
            target
        } finally { call = null; mutable.value = ModelTransfer() }
    }
}
