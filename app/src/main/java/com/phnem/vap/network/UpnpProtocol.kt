package com.phnem.vap.network

import org.w3c.dom.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/** Adapted from Vetro DlnaProtocol: SSDP, description and SOAP; extended with ContentDirectory. */
object UpnpProtocol {
    const val CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:1"
    const val MEDIA_SERVER = "urn:schemas-upnp-org:device:MediaServer:1"
    data class Server(val name: String, val location: String, val control: String, val serviceType: String)
    fun search(target: String) = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\nUSER-AGENT: Android/UPnP/1.1 VAP/1\r\n\r\n"
    fun location(packet: String): String? {
        val lines = packet.lineSequence().toList()
        if (!lines.firstOrNull().orEmpty().uppercase(Locale.ROOT).startsWith("HTTP/1.1 200")) return null
        return lines.drop(1).firstOrNull { it.substringBefore(':').equals("LOCATION", true) }?.substringAfter(':')?.trim()
            ?.takeIf { runCatching { NetworkUrls.parse(it) }.isSuccess }
    }
    fun describe(xml: String, location: String): Server? {
        val document = SafeXml.parse(xml)
        val origin = NetworkUrls.parse(location)
        val advertised = SafeXml.text(document.documentElement, "URLBase")?.let { runCatching { NetworkUrls.parse(it) }.getOrNull() } ?: origin
        require(origin.scheme == advertised.scheme && origin.host == advertised.host && origin.port == advertised.port)
        val devices = document.getElementsByTagNameNS("*", "device")
        for (index in 0 until devices.length) {
            val device = devices.item(index) as Element
            if (!SafeXml.text(device, "deviceType").orEmpty().startsWith("urn:schemas-upnp-org:device:MediaServer:")) continue
            val serviceList = SafeXml.children(device, "serviceList").firstOrNull() ?: continue
            val service = SafeXml.children(serviceList, "service").firstOrNull { SafeXml.text(it, "serviceType").orEmpty().startsWith("urn:schemas-upnp-org:service:ContentDirectory:") } ?: continue
            val control = advertised.resolve(SafeXml.text(service, "controlURL") ?: continue) ?: continue
            if (control.scheme != origin.scheme || control.host != origin.host || control.port != origin.port || control.username.isNotEmpty() || control.password.isNotEmpty()) continue
            return Server(SafeXml.text(device, "friendlyName").orEmpty().ifBlank { origin.host }.take(150), location, control.toString(), SafeXml.text(service, "serviceType")!!)
        }
        return null
    }
    fun envelope(service: String, action: String, args: List<Pair<String, String>>): String {
        require(service.matches(Regex("urn:schemas-upnp-org:service:[A-Za-z]+:[0-9]+")) && action.matches(Regex("[A-Za-z]+")))
        val body = args.joinToString("") { (key, value) -> require(key.matches(Regex("[A-Za-z]+"))); "<$key>${SafeXml.escape(value)}</$key>" }
        return """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$service">$body</u:$action></s:Body></s:Envelope>"""
    }
    fun values(xml: String): Map<String, String> {
        val nodes = SafeXml.parse(xml).getElementsByTagName("*")
        return (0 until nodes.length).mapNotNull { i -> val element = nodes.item(i) as Element
            if ((0 until element.childNodes.length).any { element.childNodes.item(it) is Element }) null
            else (element.localName ?: element.tagName.substringAfter(':')) to element.textContent.orEmpty()
        }.toMap()
    }
    fun folder(connectionId: String, objectId: String) = "upnp://$connectionId/${URLEncoder.encode(objectId, "UTF-8") }"
    fun objectId(connectionId: String, value: String): String {
        if (!value.startsWith("upnp://")) return "0"
        val uri = URI(value); require(uri.host == connectionId && uri.rawQuery == null && uri.rawFragment == null)
        return URLDecoder.decode(uri.rawPath.removePrefix("/"), "UTF-8").also { require(it.length in 1..1024) }
    }
    fun entries(xml: String, connectionId: String): List<NetworkEntry> {
        if (xml.isBlank()) return emptyList()
        val document = SafeXml.parse(xml)
        require(document.documentElement.localName == "DIDL-Lite" || document.documentElement.tagName == "DIDL-Lite")
        val root = document.documentElement
        val children = (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
        require(children.size <= 5000)
        return children.mapNotNull { item ->
            val name = SafeXml.text(item, "title")?.take(250) ?: return@mapNotNull null
            if (item.localName == "container") {
                val id = item.getAttribute("id").takeIf { it.length in 1..1024 } ?: return@mapNotNull null
                NetworkEntry(folder(connectionId, id), name, true, null)
            } else {
                val resource = SafeXml.children(item, "res").firstOrNull { resource ->
                    val mime = resource.getAttribute("protocolInfo").split(':').getOrNull(2).orEmpty()
                    (mime.startsWith("video/") || "mpegurl" in mime || mime == "application/dash+xml" || WebDavBrowser.isVideo(name)) &&
                        runCatching { NetworkUrls.parse(resource.textContent.trim()) }.isSuccess
                } ?: return@mapNotNull null
                val mime = resource.getAttribute("protocolInfo").split(':').getOrNull(2)?.takeIf { it.startsWith("video/") || "mpegurl" in it || it == "application/dash+xml" }
                NetworkEntry(NetworkUrls.parse(resource.textContent.trim()).toString(), name, false, resource.getAttribute("size").toLongOrNull()?.takeIf { it >= 0 }, mime)
            }
        }
    }
}
