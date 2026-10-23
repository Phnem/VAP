package com.phnem.vap.network

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Adapted from Vetro's PlaybackAuthScope.forPlaybackCandidate. Authenticated redirects are refused. */
class ScopedNetworkAuth(private val connections: () -> List<NetworkConnection>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val original = chain.request()
        val request = original.newBuilder().removeHeader("Authorization").removeHeader("Cookie").removeHeader("X-Emby-Token").removeHeader("X-Plex-Token").removeHeader("X-Emby-Authorization")
            .removeHeader("X-Plex-Client-Identifier").removeHeader("X-Plex-Product").removeHeader("X-Plex-Version").build()
        val credential = connections().filter { ((it.kind in setOf(NetworkKind.URL, NetworkKind.WEBDAV) && it.username.isNotEmpty()) || it.isServer) && it.contains(request.url) }.maxByOrNull { it.root.encodedPath.length }
        val authenticated = if (credential == null) request else request.newBuilder().apply {
            when(credential.kind) {
                NetworkKind.JELLYFIN, NetworkKind.EMBY -> {
                    header("X-Emby-Authorization", "${if (credential.kind == NetworkKind.EMBY) "Emby" else "MediaBrowser"} Client=\"VAP\", Device=\"Android\", DeviceId=\"${credential.id}\", Version=\"${com.phnem.vap.BuildConfig.VERSION_NAME}\"")
                    if (credential.token.isNotEmpty()) header("X-Emby-Token", credential.token)
                }
                NetworkKind.PLEX -> {
                    header("X-Plex-Client-Identifier", credential.id); header("X-Plex-Product", "VAP"); header("X-Plex-Version", com.phnem.vap.BuildConfig.VERSION_NAME)
                    if (credential.token.isNotEmpty()) header("X-Plex-Token", credential.token)
                }
                else -> header("Authorization", Credentials.basic(credential.username, credential.password, Charsets.UTF_8))
            }
        }.build()
        val response = chain.proceed(authenticated)
        if (credential != null && response.code in 300..399) {
            response.close(); throw IOException("Authenticated redirect requires a direct server URL")
        }
        return response
    }
}

object NetworkHttp {
    private val base = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .followSslRedirects(false).retryOnConnectionFailure(true).build()
    fun playback(context: Context, connectionId: String? = null): OkHttpClient {
        val store = NetworkConnectionStore(context.applicationContext)
        return base.newBuilder().addNetworkInterceptor(ScopedNetworkAuth { runBlocking { store.list().filter { connectionId == null || it.id == connectionId } } }).build()
    }
    fun connection(connection: NetworkConnection): OkHttpClient = base.newBuilder().followRedirects(false)
        .addNetworkInterceptor(ScopedNetworkAuth { listOf(connection) }).build()
}
