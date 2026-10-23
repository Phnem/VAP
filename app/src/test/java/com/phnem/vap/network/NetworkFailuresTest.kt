package com.phnem.vap.network

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NetworkFailuresTest {
    @Test fun credentialsAreNotRetriedThroughWrappers() {
        assertTrue(NetworkFailures.permanent(IOException("source", IOException("open", NetworkAuthFailure()))))
        assertTrue(NetworkFailures.permanent(net.schmizz.sshj.userauth.UserAuthException("denied")))
        assertTrue(NetworkFailures.permanent(net.schmizz.sshj.common.SSHException(net.schmizz.sshj.common.DisconnectReason.HOST_KEY_NOT_VERIFIABLE)))
    }
    @Test fun timeoutsCanRecover() {
        assertFalse(NetworkFailures.permanent(java.net.SocketTimeoutException()))
        assertFalse(NetworkFailures.permanent(java.net.ConnectException()))
    }
}
