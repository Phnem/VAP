package com.phnem.vap.network

import org.junit.Assert.*
import org.junit.Test

class UpnpProtocolTest {
    @Test fun ssdpHeadersAreCaseInsensitiveAndUrlsValidated() {
        assertEquals("http://192.168.1.2/device.xml", UpnpProtocol.location("HTTP/1.1 200 OK\r\nlocation: http://192.168.1.2/device.xml\r\n"))
        assertNull(UpnpProtocol.location("HTTP/1.1 404 Missing\r\nLOCATION: http://a.test/"))
        assertNull(UpnpProtocol.location("HTTP/1.1 200 OK\r\nLOCATION: file:///secret"))
    }
    @Test fun mediaServerControlUsesAdvertisedServiceVersion() {
        val server = UpnpProtocol.describe(description, "http://nas.test/root/device.xml")!!
        assertEquals("NAS & films", server.name)
        assertEquals("http://nas.test/root/control", server.control)
        assertEquals("urn:schemas-upnp-org:service:ContentDirectory:2", server.serviceType)
    }
    @Test fun controlCannotMoveToAnotherOrigin() {
        assertNull(UpnpProtocol.describe(description.replace("<controlURL>control</controlURL>", "<controlURL>http://evil.test/steal</controlURL>"), "http://nas.test/device.xml"))
        assertTrue(runCatching { UpnpProtocol.describe("<!DOCTYPE root SYSTEM 'https://evil.test/'>" + description, "http://nas.test/device.xml") }.isFailure)
    }
    @Test fun objectIdsSurviveEscapingAndRemainConnectionScoped() {
        val id = "7/films & clips+one"
        assertEquals(id, UpnpProtocol.objectId("test-id", UpnpProtocol.folder("test-id", id)))
        assertTrue(runCatching { UpnpProtocol.objectId("other-id", UpnpProtocol.folder("test-id", id)) }.isFailure)
        val body = UpnpProtocol.envelope(UpnpProtocol.CONTENT_DIRECTORY, "Browse", listOf("ObjectID" to "7<&"))
        assertEquals("7<&", UpnpProtocol.values(body)["ObjectID"])
    }
    @Test fun didlFiltersNonVideoAndKeepsManifestType() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/">
            <container id="films"><dc:title>Films</dc:title></container>
            <item id="video"><dc:title>Clip</dc:title><res protocolInfo="http-get:*:application/dash+xml:*" size="123">https://nas.test/play?id=7</res></item>
            <item id="audio"><dc:title>Song</dc:title><res protocolInfo="http-get:*:audio/mpeg:*">https://nas.test/song.mp3</res></item>
            <item id="bad"><dc:title>Bad.mp4</dc:title><res protocolInfo="http-get:*:video/mp4:*">file:///secret</res></item>
        </DIDL-Lite>"""
        val items = UpnpProtocol.entries(didl, "test-id")
        assertEquals(listOf("Films", "Clip"), items.map { it.name })
        assertEquals("application/dash+xml", items.last().mimeType)
    }
    private val description = """<root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType><friendlyName>NAS &amp; films</friendlyName><serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:2</serviceType><controlURL>control</controlURL></service></serviceList></device></root>"""
}
