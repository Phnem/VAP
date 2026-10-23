package com.phnem.vap.network

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException

class NetworkAuthFailure(cause: Throwable? = null) : IOException("Server authentication or host verification rejected", cause)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object NetworkFailures {
    fun permanent(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(12).any {
        it is NetworkAuthFailure || it is net.schmizz.sshj.userauth.UserAuthException ||
            (it is net.schmizz.sshj.common.SSHException && it.disconnectReason == net.schmizz.sshj.common.DisconnectReason.HOST_KEY_NOT_VERIFIABLE) ||
            (it is HttpDataSource.InvalidResponseCodeException && it.responseCode in setOf(400, 401, 403, 404, 405, 410, 416)) ||
            (it is com.hierynomus.mssmb2.SMBApiException && it.statusCode in setOf(0xc0000022L, 0xc0000034L, 0xc000003aL, 0xc000006dL, 0xc0000234L))
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NetworkLoadErrors : DefaultLoadErrorHandlingPolicy(3) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (NetworkFailures.permanent(loadErrorInfo.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
}
