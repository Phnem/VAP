package com.phnem.vap.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

data class HttpReply(val code: Int, val bytes: ByteArray, val retryAfterSeconds: Int = 0, val location: String? = null)
fun interface BoundedHttp {
    suspend fun request(url: String, method: String, headers: Map<String, String>, body: ByteArray?, limit: Int): HttpReply
}

class PlatformHttp(private val readTimeoutMs: Int = 15_000) : BoundedHttp {
    override suspend fun request(url: String, method: String, headers: Map<String, String>, body: ByteArray?, limit: Int): HttpReply = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 12_000; connection.readTimeout = readTimeoutMs
        headers.forEach(connection::setRequestProperty)
        try {
            if (body != null) { connection.doOutput = true; connection.setFixedLengthStreamingMode(body.size); connection.outputStream.use { it.write(body) } }
            val code = connection.responseCode
            val bytes = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size().toLong() + count <= limit) { "Response exceeds size limit" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            } ?: ByteArray(0)
            HttpReply(code, bytes, connection.getHeaderField("Retry-After")?.toIntOrNull()?.coerceIn(0, 60) ?: 0, connection.getHeaderField("Location"))
        } finally { connection.disconnect() }
    }
}
