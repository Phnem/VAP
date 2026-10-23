package com.phnem.vap.network

import org.junit.Assert.*
import org.junit.Test

class NetworkSourcesTest {
    @Test fun credentialsStayWithinOriginAndPath() {
        val root = NetworkUrls.directory("https://nas.test:8443/videos/")
        assertTrue(NetworkUrls.contains(root, NetworkUrls.parse("https://nas.test:8443/videos/film.mp4")))
        listOf("https://nas.test/videos/film.mp4", "http://nas.test:8443/videos/film.mp4", "https://nas.test:8443/videos2/film.mp4",
            "https://other.test:8443/videos/film.mp4", "https://nas.test:8443/film.mp4").forEach {
            assertFalse(it, NetworkUrls.contains(root, NetworkUrls.parse(it)))
        }
    }
    @Test fun embeddedCredentialsAndEncodedSlashesRejected() {
        listOf("https://user:secret@nas.test/film.mp4", "https://nas.test/a%2Fb/film.mp4", "https://nas.test/a%5Cb/film.mp4",
            "https://nas.test/%252e%252e/film.mp4", "file:///sdcard/video.mp4").forEach { value ->
            assertTrue(value, runCatching { NetworkUrls.parse(value) }.isFailure)
        }
    }
    @Test fun davListsOnlyDirectChildrenAndStaysInsideRoot() {
        val xml = """<d:multistatus xmlns:d="DAV:">
          <d:response><d:href>/videos/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
          <d:response><d:href>/videos/Zeta.mp4</d:href><d:propstat><d:prop><d:displayname>Zeta.mp4</d:displayname><d:getcontentlength>1234</d:getcontentlength></d:prop></d:propstat></d:response>
          <d:response><d:href>/videos/season/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
          <d:response><d:href>/videos/season/episode.mkv</d:href></d:response>
          <d:response><d:href>https://evil.test/steal.mp4</d:href></d:response>
          <d:response><d:href>/outside.mp4</d:href></d:response>
          <d:response><d:href>/videos/readme.txt</d:href></d:response>
        </d:multistatus>"""
        val items = WebDavBrowser.parse(xml, "https://nas.test/videos/", "https://nas.test/videos/")
        assertEquals(listOf("season", "Zeta.mp4"), items.map { it.name })
        assertEquals(1234L, items.last().size)
    }
    @Test fun relativeDavPathsResolveAgainstCurrentFolder() {
        val xml = """<multistatus xmlns="DAV:"><response><href>episode%20one.mkv</href></response></multistatus>"""
        val items = WebDavBrowser.parse(xml, "https://nas.test/videos/", "https://nas.test/videos/season/")
        assertEquals("https://nas.test/videos/season/episode%20one.mkv", items.single().url)
    }
    @Test fun externalEntitiesRejected() {
        assertTrue(runCatching { WebDavBrowser.parse("""<!DOCTYPE x [<!ENTITY secret SYSTEM "file:///etc/passwd">]><x>&secret;</x>""",
            "https://nas.test/", "https://nas.test/") }.isFailure)
    }
    @Test fun secretsDoNotAppearInConnectionToString() {
        val connection = NetworkConnection(name = "name", kind = NetworkKind.URL, url = "https://nas.test/a.mp4?token=secret-token", username = "user", password = "secret-password")
        assertFalse(connection.toString().contains("secret"))
        assertEquals("/", connection.root.encodedPath)
    }
}
