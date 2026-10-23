package com.phnem.vap

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.phnem.vap.network.*
import com.phnem.vap.player.*
import com.phnem.vap.player.model.PlayableItem
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Instrumentation.runNetworkDeviceTests() {
    val store = NetworkConnectionStore(targetContext)
    val server = NetworkFixtureServer(File(targetContext.filesDir, "network-fixtures"))
    val foreign = NetworkFixtureServer(File(targetContext.filesDir, "network-fixtures"), requireAuth = false)
    val connection = NetworkConnection(id = "vap-network-device-test", name = "VAP network fixture ${java.net.URI(server.base).port}", kind = NetworkKind.WEBDAV, url = "${server.base}/protected/", username = "fixture", password = "fixture-secret")
    val dlna = NetworkConnection(id = "vap-dlna-device-test", name = "UPnP fixture", kind = NetworkKind.DLNA, url = "${server.base}/device.xml")
    val oldLibrarySession = com.phnem.vap.library.TreeStore(targetContext).session()
    var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var player: MediaController? = null; var activity: MainActivity? = null; var failure: Throwable? = null
    var playerActivity: PlayerActivity? = null
    val oldBuffer = NetworkBuffer.read(targetContext)
    try {
        server.redirectTo = "${foreign.base}/leak"
        runBlocking {
            store.list().filter { it.name.startsWith("VAP network fixture") && runCatching { java.net.URI(it.url).host == "127.0.0.1" }.getOrDefault(false) && it.username == "fixture" && it.password == "fixture-secret" }.forEach { store.remove(it.id) }
            store.save(connection)
            store.save(dlna)
            check(NetworkConnectionStore(targetContext).list().first { it.id == connection.id }.password == "fixture-secret")
            val raw = targetContext.getSharedPreferences("network_connections", 0).getString("connections", null).orEmpty()
            check("fixture-secret" !in raw && "protected" !in raw)
            val items = WebDavBrowser.list(connection, connection.root.toString())
            check(items.map { it.name } == listOf("season", "video.mp4")) { "Bad DAV items: $items" }
            check(WebDavBrowser.list(connection, "${server.base}/protected/season/").single().name == "episode.mp4")
            val folders = DlnaBrowser.list(dlna, dlna.rootUrl)
            check(folders.single().name == "Films" && folders.single().folder)
            val clips = DlnaBrowser.list(dlna, folders.single().url)
            check(clips.single().name == "VAP DLNA clip" && clips.single().mimeType == "video/mp4")
        }
        NetworkHttp.playback(targetContext).newCall(Request.Builder().url("${server.base}/protected/redirect").build()).let { call ->
            check(runCatching { call.execute().use { } }.isFailure) { "Authenticated redirect followed" }
        }
        check(foreign.requests.get() == 0) { "Credentials could reach redirected server" }
        NetworkHttp.playback(targetContext).newCall(Request.Builder().url("${foreign.base}/outside").header("Authorization", "unsafe").build()).execute().close()
        check(foreign.authReceived.get() == 0)

        runOnMainSync { future = PlaybackConnection.connect(targetContext) }
        player = future!!.get(15, TimeUnit.SECONDS); val controller = player!!
        for ((path, profile) in listOf("video.mp4" to NetworkBuffer.NORMAL, "hls/stream.m3u8" to NetworkBuffer.LOW, "dash/manifest.mpd" to NetworkBuffer.LARGE, "hls/play" to NetworkBuffer.NORMAL)) {
            NetworkBuffer.save(targetContext, profile)
            runOnMainSync { controller.setMediaItem(PlayableItem("${server.base}/protected/$path", "Network $path", if (path == "hls/play") "application/x-mpegURL" else null).toMediaItem()); controller.prepare(); controller.play() }
            if (playerActivity == null) {
                val playerMonitor = addMonitor(PlayerActivity::class.java.name, null, false)
                uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n com.phnem.vap/.player.PlayerActivity -a ${PlaybackService.ACTION_RESUME}").close()
                playerActivity = waitForMonitorWithTimeout(playerMonitor, 10000) as? PlayerActivity
                removeMonitor(playerMonitor); check(playerActivity != null)
            }
            awaitNetwork("$path decoded", 30000) { uiRead { controller.playbackState == Player.STATE_READY && controller.currentPosition > 500 && controller.videoSize.width > 0 } }
            runOnMainSync { controller.pause(); controller.seekTo(15_000) }
            awaitNetwork("$path seek", 20000) { uiRead { controller.playbackState == Player.STATE_READY && controller.currentPosition in 14_900..15_100 } }
            uiMark("$path actual network decode/seek passed, profile=$profile")
        }
        check(server.ranges.get() > 0 && server.authReceived.get() > 0)
        // Start with a failing HTTP source. Media3's load retries and the service recovery must
        // survive a short outage and preserve the requested position without changing source.
        server.outageUntil = SystemClock.elapsedRealtime() + 7000
        val outageUrl = "${server.base}/protected/outage.mp4"
        runOnMainSync { controller.setMediaItem(PlayableItem(outageUrl, "Network reconnect").toMediaItem(), 5000); controller.prepare(); controller.play() }
        awaitNetwork("reconnect after transient 503", 35000) { uiRead { controller.playerError == null && controller.playbackState == Player.STATE_READY && controller.currentPosition >= 5000 } }
        check(server.unavailable.get() > 0)
        runOnMainSync { controller.pause() }

        targetContext.getSharedPreferences("vap_app", 0).edit().putBoolean("first_run_done", true).commit() // the first-run screen would cover the library under test
        val monitor = addMonitor(MainActivity::class.java.name, null, false)
        uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n com.phnem.vap/.MainActivity").close()
        activity = waitForMonitorWithTimeout(monitor, 10000) as? MainActivity; removeMonitor(monitor); check(activity != null)
        clickUi(labelNode(targetContext.getString(R.string.resume_session_later)))
        clickUi(labelNode(targetContext.getString(R.string.library_title)))
        clickUi(labelNode(targetContext.getString(R.string.network_sources)))
        labelNode(connection.name)
        val titleBounds = android.graphics.Rect(); labelNode(connection.name).getBoundsInScreen(titleBounds)
        clickUi(awaitNode { node ->
            val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
            node.text?.toString() == targetContext.getString(R.string.action_open) && bounds.top >= titleBounds.bottom && bounds.top < titleBounds.bottom + 350
        })
        labelNode("▶ video.mp4"); labelNode("▸ season"); uiScreenshot("network-webdav.png")
        clickUi(labelNode(targetContext.getString(R.string.network_up)))
        val shot = uiAutomation.takeScreenshot(); val width = shot.width.toFloat(); val height = shot.height.toFloat(); shot.recycle()
        repeat(8) {
            val node = findUiNode { it.text?.toString() == dlna.name }; val bounds = android.graphics.Rect(); node?.getBoundsInScreen(bounds)
            if (node == null || bounds.height() < 20 || bounds.bottom > height * .85f) uiDrag(width * .8f, height * .8f, width * .8f, height * .4f)
        }
        val dlnaTitle = android.graphics.Rect(); labelNode(dlna.name).getBoundsInScreen(dlnaTitle)
        clickUi(awaitNode { node ->
            val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
            node.text?.toString() == targetContext.getString(R.string.action_open) && bounds.top >= dlnaTitle.bottom && bounds.top < dlnaTitle.bottom + 350
        })
        clickUi(labelNode("▸ Films")); labelNode("▶ VAP DLNA clip"); uiScreenshot("network-dlna.png")
        val videoMonitor = addMonitor(PlayerActivity::class.java.name, null, false)
        clickUi(labelNode("▶ VAP DLNA clip"))
        playerActivity = waitForMonitorWithTimeout(videoMonitor, 10000) as? PlayerActivity
        removeMonitor(videoMonitor); check(playerActivity != null)
        awaitNetwork("DLNA item actual decode", 20000) { uiRead { controller.playbackState == Player.STATE_READY && controller.currentPosition > 300 && controller.videoSize.width > 0 && controller.currentMediaItem?.localConfiguration?.mimeType == "video/mp4" } }
    } catch (e: Throwable) {
        failure = e
        runCatching { uiMark(uiRead { "state=${player?.playbackState} pos=${player?.currentPosition} size=${player?.videoSize} error=${player?.playerError?.let(android.util.Log::getStackTraceString)} requests=${server.requests.get()} auth=${server.authReceived.get()} ranges=${server.ranges.get()}" }) }
        runCatching { uiScreenshot("network-failure.png") }
    } finally {
        runOnMainSync { player?.pause(); player?.clearMediaItems(); activity?.finish(); playerActivity?.finish(); future?.let { MediaController.releaseFuture(it) } }
        runBlocking { store.remove(connection.id); store.remove(dlna.id) }
        com.phnem.vap.library.TreeStore(targetContext).saveSession(oldLibrarySession)
        NetworkBuffer.save(targetContext, oldBuffer)
        server.close(); foreign.close()
    }
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
        putString("stream", if (failure == null) "PASS: encrypted connections, DAV and UPnP folders, credential boundary, actual HTTP/HLS/DASH and extensionless HLS decode/seek, buffer profiles, 503 reconnect, native DAV/DLNA UI and DLNA item playback; physical SSDP/NAS not tested\n" else "FAIL: ${android.util.Log.getStackTraceString(failure)}")
    })
}

private fun awaitNetwork(label: String, timeoutMs: Long, condition: () -> Boolean) {
    val end = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < end) { if (condition()) return; SystemClock.sleep(100) }
    error("Timed out: $label")
}

internal class NetworkFixtureServer(private val files: File, private val requireAuth: Boolean = true) : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    val base = "http://127.0.0.1:${server.localPort}"
    val requests = AtomicInteger(); val authReceived = AtomicInteger(); val ranges = AtomicInteger(); val unavailable = AtomicInteger()
    @Volatile var redirectTo = ""
    @Volatile var outageUntil = 0L
    init { workers.execute { while (!server.isClosed) { val socket = try { server.accept() } catch (_: Exception) { break }; workers.execute { runCatching { serve(socket) }; socket.close() } } } }
    private fun serve(socket: Socket) {
        socket.soTimeout = 15000
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val first = input.readLine()?.split(' ') ?: return
        val headers = mutableMapOf<String, String>()
        while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
        val method = first[0]; val path = first.getOrNull(1)?.substringBefore('?') ?: return
        requests.incrementAndGet(); if (headers["authorization"] != null) authReceived.incrementAndGet()
        val output = socket.getOutputStream()
        fun respond(code: String, body: ByteArray = ByteArray(0), extra: String = "", type: String = "application/octet-stream") {
            output.write("HTTP/1.1 $code\r\nConnection: close\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n$extra\r\n".toByteArray())
            if (method != "HEAD") output.write(body)
            output.flush()
        }
        if (requireAuth && path.startsWith("/protected/") && headers["authorization"] != "Basic Zml4dHVyZTpmaXh0dXJlLXNlY3JldA==") { respond("401 Unauthorized", extra = "WWW-Authenticate: Basic realm=VAP\r\n"); return }
        if (path == "/protected/redirect") { respond("302 Found", extra = "Location: $redirectTo\r\n"); return }
        if (path == "/device.xml") {
            respond("200 OK", """<root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType><friendlyName>VAP fixture NAS</friendlyName><serviceList><service><serviceType>${UpnpProtocol.CONTENT_DIRECTORY}</serviceType><controlURL>/content</controlURL></service></serviceList></device></root>""".toByteArray(), type = "text/xml"); return
        }
        if (path == "/content") {
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = CharArray(length); var read = 0
            while (read < length) { val n = input.read(body, read, length - read); if (n < 0) break; read += n }
            val id = UpnpProtocol.values(String(body))["ObjectID"]
            val items = if (id == "0") """<container id="films"><dc:title>Films</dc:title></container>""" else """<item id="clip"><dc:title>VAP DLNA clip</dc:title><res protocolInfo="http-get:*:video/mp4:*">$base/upnp-video.mp4</res></item>"""
            val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/">$items</DIDL-Lite>"""
            respond("200 OK", UpnpProtocol.envelope(UpnpProtocol.CONTENT_DIRECTORY, "BrowseResponse", listOf("Result" to didl, "NumberReturned" to "1", "TotalMatches" to "1")).toByteArray(), type = "text/xml"); return
        }
        if (method == "PROPFIND") {
            val children = if (path == "/protected/season/") """<d:response><d:href>episode.mp4</d:href></d:response>""" else """
                <d:response><d:href>/protected/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                <d:response><d:href>/protected/season/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                <d:response><d:href>/protected/video.mp4</d:href><d:propstat><d:prop><d:getcontentlength>12345</d:getcontentlength></d:prop></d:propstat></d:response>
                <d:response><d:href>http://evil.test/escape.mp4</d:href></d:response>
            """
            respond("207 Multi-Status", "<d:multistatus xmlns:d=\"DAV:\">$children</d:multistatus>".toByteArray(), type = "application/xml"); return
        }
        if (path == "/protected/outage.mp4" && SystemClock.elapsedRealtime() < outageUntil) { unavailable.incrementAndGet(); respond("503 Unavailable"); return }
        val relative = when(path) {
            "/protected/outage.mp4", "/upnp-video.mp4" -> "video.mp4"
            "/protected/hls/play" -> "hls/stream.m3u8"
            else -> path.removePrefix("/protected/")
        }
        val file = File(files, relative)
        if (!file.isFile || !file.canonicalPath.startsWith(files.canonicalPath + File.separator)) { respond("404 Not Found"); return }
        val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val start = range?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val end = range?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(file.length() - 1) ?: (file.length() - 1)
        if (start > end || start >= file.length()) { respond("416 Range Not Satisfiable"); return }
        if (range != null) ranges.incrementAndGet()
        val count = end - start + 1
        val type = when(file.extension) { "m3u8" -> "application/vnd.apple.mpegurl"; "mpd" -> "application/dash+xml"; "ts" -> "video/mp2t"; "mp4", "m4s" -> "video/mp4"; else -> "application/octet-stream" }
        val rangeHeader = if (range != null) "Content-Range: bytes $start-$end/${file.length()}\r\n" else ""
        output.write("HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\nConnection: close\r\nAccept-Ranges: bytes\r\nContent-Type: $type\r\nContent-Length: $count\r\n$rangeHeader\r\n".toByteArray())
        if (method != "HEAD") java.io.RandomAccessFile(file, "r").use { source -> source.seek(start); var remaining = count; val buffer = ByteArray(32768)
            while (remaining > 0) { val n = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); if (n < 0) break; output.write(buffer, 0, n); remaining -= n } }
        output.flush()
    }
    override fun close() { server.close(); workers.shutdownNow() }
}
