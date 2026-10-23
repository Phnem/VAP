package com.phnem.vap.network

import org.junit.Assert.*
import org.junit.Test

class RemoteAddressTest {
    @Test fun siblingAndParentPathsStayScoped() {
        val root = RemoteAddress.parse("smb://nas.test/MEDIA/videos/")
        val child = root.child("серия 10%.mkv", false)
        assertTrue(root.contains(child))
        assertEquals("/MEDIA/videos/серия 10%.mkv", child.path)
        assertEquals(root.url, child.parent().url)
        assertFalse(root.contains(RemoteAddress.parse("smb://nas.test/MEDIA/videos2/a.mkv")))
        assertFalse(root.contains(RemoteAddress.parse("smb://evil.test/MEDIA/videos/a.mkv")))
        assertFalse(root.contains(RemoteAddress.parse("smb://nas.test:9999/MEDIA/videos/a.mkv")))
    }
    @Test fun noTraversalOrEmbeddedCredentials() {
        listOf("ftp://a.test/media/../secret", "sftp://u:p@a.test/media/", "smb://a.test/MEDIA/a%2fb", "ftp://a.test/media/%0d%0aQUIT").forEach {
            assertTrue(it, runCatching { RemoteAddress.parse(it) }.isFailure)
        }
    }
    @Test fun sftpRequiresExplicitSha256Pin() {
        assertTrue(runCatching { NetworkConnection(name = "SFTP", kind = NetworkKind.SFTP, url = "sftp://a.test/media/") }.isFailure)
        NetworkConnection(name = "SFTP", kind = NetworkKind.SFTP, url = "sftp://a.test/media/", hostKey = "SHA256:" + "A".repeat(43))
    }
    @Test fun protocolMustMatchConnection() {
        assertTrue(runCatching { NetworkConnection(name = "SMB", kind = NetworkKind.SMB, url = "ftp://a.test/MEDIA/") }.isFailure)
        assertTrue(runCatching { NetworkConnection(name = "SMB", kind = NetworkKind.SMB, url = "smb://a.test/") }.isFailure)
        assertEquals(22, RemoteAddress.parse("sftp://a.test/media/").port)
    }
}
