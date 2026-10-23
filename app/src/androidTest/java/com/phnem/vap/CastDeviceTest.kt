package com.phnem.vap

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.phnem.vap.cast.*
import com.phnem.vap.cast.dlna.DlnaProtocol
import com.phnem.vap.player.*
import com.phnem.vap.player.model.PlayableItem
import com.phnem.vap.player.settings.PlayerPreferences
import com.phnem.vap.player.subtitles.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Instrumentation.runCastDeviceTests() {
    val fixture = RendererFixture()
    val preferences = PlayerPreferences(targetContext)
    var runtime: RemoteRuntime? = null
    val singleton = RemoteRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }
    val oldSingleton = singleton.get(null)
    var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var controller: MediaController? = null
    var activity: PlayerActivity? = null
    var source: SubtitleSource? = null
    var failure: Throwable? = null
    var previousItems: List<androidx.media3.common.MediaItem> = emptyList(); var previousIndex = 0; var previousPosition = 0L
    val castFile = File(targetContext.filesDir, "vap-cast-fixture.mp4")
    val fixtureId = Uri.fromFile(castFile).toString()
    try {
        val sample = File(targetContext.filesDir, "vap-test.mp4").copyTo(castFile, overwrite = true)
        val sampleBytes = sample.readBytes(); val expected = sampleBytes.copyOf(512)
        runBlocking {
            source = SubtitleStorage(targetContext).create("Cast captions", "en", listOf(SubtitleLine(1000, 5000, "VAP caption")))
            preferences.setSubtitles(fixtureId, SubtitleSettings(selection = source!!.id, sources = listOf(source!!), delayMs = 500))
        }
        runOnMainSync {
            future = PlaybackConnection.connect(targetContext)
            runtime = RemoteRuntime(targetContext, addressFor = { InetAddress.getByName("127.0.0.1") as Inet4Address }, lanAvailable = { true })
            singleton.set(null, runtime)
        }
        controller = future!!.get(15, TimeUnit.SECONDS)
        val player = controller!!; val remote = runtime!!
        runOnMainSync { previousItems = List(player.mediaItemCount, player::getMediaItemAt); previousIndex = player.currentMediaItemIndex.coerceAtLeast(0); previousPosition = player.currentPosition.coerceAtLeast(0) }
        runOnMainSync {
            player.setMediaItem(PlayableItem(Uri.fromFile(sample).toString(), "Cast fixture", "video/mp4").toMediaItem())
            player.prepare(); player.pause()
        }
        val monitor = addMonitor(PlayerActivity::class.java.name, null, false)
        uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n com.phnem.vap/.player.PlayerActivity -a ${PlaybackService.ACTION_RESUME}").close()
        activity = waitForMonitorWithTimeout(monitor, 10000) as? PlayerActivity; removeMonitor(monitor); check(activity != null)
        try { awaitUi("cast local decoder ready") { uiRead { player.playbackState == Player.STATE_READY && player.duration > 0 } } }
        catch(e: Exception) { throw IllegalStateException(uiRead { "state=${player.playbackState}, pos=${player.currentPosition}, intent=${player.playWhenReady}, error=${player.playerError}, media=${player.currentMediaItem?.mediaId}" }, e) }
        if (findUiNode { it.contentDescription?.toString() in listOf("Play on…", "Воспроизвести на…") } == null) {
            val shot = uiAutomation.takeScreenshot(); uiTap(shot.width * .5f, shot.height * .5f); shot.recycle()
        }
        clickUi(awaitNode { it.contentDescription?.toString() in listOf("Play on…", "Воспроизвести на…") })
        labelNode(targetContext.getString(R.string.cast_title)); labelNode(targetContext.getString(R.string.cast_renderer_url))
        clickUi(labelNode(targetContext.getString(R.string.cast_close)))
        verifyHlsProxy(player)
        runOnMainSync { player.setMediaItem(PlayableItem(Uri.fromFile(sample).toString(), "Cast fixture", "video/mp4").toMediaItem()); player.prepare(); player.pause() }
        awaitUi("local restored before casting") { uiRead { player.playbackState == Player.STATE_READY } }
        labelNode("Cast fixture")
        runOnMainSync { player.seekTo(15_000) }
        awaitUi("cast initial seek") { uiRead { player.playbackState == Player.STATE_READY && player.currentPosition in 14_900..15_100 } }
        runBlocking { remote.dlna.addRenderer(fixture.url) }
        awaitUi("renderer published") { remote.manager.devices.value.isNotEmpty() }
        val device = remote.manager.devices.value.single()
        check(device.primary.capabilities.volume && device.primary.capabilities.subtitles)
        runBlocking(Dispatchers.Main) { remote.cast(device, true) }
        awaitUi("renderer playing at saved position") { remote.manager.playback.value.let { it.status == RemoteStatus.PLAYING && it.positionMs >= 14_000 } }
        check(uiRead { !player.playWhenReady })
        check(fixture.bytes.contentEquals(expected)) { "Local proxy range changed video bytes" }
        check(fixture.metadata.contains("CaptionInfoEx"))
        val client = OkHttpClient()
        client.newCall(Request.Builder().url(fixture.mediaUrl.replace("/main.mp4", "/sub/0.srt")).build()).execute().use {
            check(it.isSuccessful); val text = it.body!!.string(); check("00:00:01,500" in text && "VAP caption" in text)
        }
        client.newCall(Request.Builder().url(fixture.mediaUrl).header("Range", "bytes=-16").build()).execute().use {
            check(it.code == 206 && it.body!!.bytes().contentEquals(sampleBytes.copyOfRange(sampleBytes.size - 16, sampleBytes.size)))
        }
        client.newCall(Request.Builder().url(fixture.mediaUrl).header("Range", "bytes=999999999999-").build()).execute().use { check(it.code == 416) }
        client.newCall(Request.Builder().url(fixture.mediaUrl.replace(Regex("/s/[^/]+/"), "/s/unknown/")).build()).execute().use { check(it.code == 404) }
        awaitUi("casting foreground service") {
            (targetContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getRunningServices(100).any { it.service.className == "com.phnem.vap.cast.RemotePlaybackService" && it.foreground }
        }
        clickUi(labelNode(targetContext.getString(R.string.cast_controls)))
        clickUi(labelNode(targetContext.getString(R.string.cast_pause)))
        awaitUi("native remote pause") { remote.manager.playback.value.status == RemoteStatus.PAUSED }
        android.os.SystemClock.sleep(400); uiScreenshot("cast-connected.png")
        runBlocking(Dispatchers.Main) { remote.manager.seek(23_000); remote.manager.setVolume(.37f) }
        awaitUi("remote pause seek volume") { remote.manager.playback.value.let { it.status == RemoteStatus.PAUSED && it.positionMs == 23_000L && fixture.volume == 37 } }
        clickUi(labelNode(targetContext.getString(R.string.cast_play)))
        awaitUi("native remote play") { remote.manager.playback.value.status == RemoteStatus.PLAYING }
        clickUi(labelNode(targetContext.getString(R.string.cast_return)))
        awaitUi("return local decoder at remote position") { uiRead { player.playWhenReady && player.playbackState == Player.STATE_READY && player.currentPosition in 23_000..25_000 } }
        awaitUi("casting service released") {
            (targetContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getRunningServices(100).none { it.service.className == "com.phnem.vap.cast.RemotePlaybackService" }
        }
        check(!remote.active && fixture.actions.containsAll(listOf("SetAVTransportURI", "Play", "Pause", "Seek", "SetVolume", "Stop")))
        check(runCatching { client.newCall(Request.Builder().url(fixture.mediaUrl).build()).execute().close() }.isFailure) { "Proxy remained open after disconnect" }
        runOnMainSync { player.pause() }
        // The notification stop action keeps its foreground service until network teardown finishes.
        runBlocking(Dispatchers.Main) { remote.cast(device, false) }
        awaitUi("second casting foreground service") {
            (targetContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getRunningServices(100).any { it.service.className == "com.phnem.vap.cast.RemotePlaybackService" && it.foreground }
        }
        targetContext.startService(android.content.Intent(targetContext, RemotePlaybackService::class.java).setAction("com.phnem.vap.cast.STOP"))
        awaitUi("notification stop completed") { !remote.active }
        awaitUi("notification foreground released") {
            (targetContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getRunningServices(100).none { it.service.className == "com.phnem.vap.cast.RemotePlaybackService" }
        }
        check(uiRead { !player.playWhenReady })
        android.os.SystemClock.sleep(400)
        uiScreenshot("cast-devices.png")
    } catch (e: Throwable) { failure = e; runCatching { uiScreenshot("cast-failure.png") } }
    finally {
        runtime?.let { runBlocking(Dispatchers.Main) { it.close() } }
        runOnMainSync {
            singleton.set(null, oldSingleton)
            activity?.finish()
            controller?.let { it.pause(); it.setMediaItems(previousItems, previousIndex.coerceAtMost((previousItems.size - 1).coerceAtLeast(0)), previousPosition) }
            future?.let { MediaController.releaseFuture(it) }
        }
        runBlocking { preferences.resetFile(fixtureId); source?.let { SubtitleStorage(targetContext).delete(it.id) } }
        fixture.close(); castFile.delete()
    }
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
        putString("stream", if (failure == null) "PASS: actual HLS proxy rewrite/decode/seek and DLNA SOAP connect/load/play/pause/seek/volume/stop, local-file proxy ranges and captions, closed proxy after return, local decoder resumes remote position, native picker/connected overlay/play/pause/return and actual foreground-service lifecycle and notification stop; physical multicast/Cast receiver not tested\n" else "FAIL: ${android.util.Log.getStackTraceString(failure)}")
    })
}

private class RendererFixture : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newFixedThreadPool(4)
    private val thread = Thread({ while (!server.isClosed) {
        val socket = try { server.accept() } catch (_: Exception) { break }
        pool.execute { runCatching { serve(socket) }; socket.close() }
    } }, "vap-renderer-fixture").apply { isDaemon = true; start() }
    val url = "http://127.0.0.1:${server.localPort}/dmr.xml"
    val actions = CopyOnWriteArrayList<String>()
    @Volatile var mediaUrl = ""
    @Volatile var metadata = ""
    @Volatile var bytes = ByteArray(0)
    @Volatile var volume = 50
    @Volatile private var position = 0L
    @Volatile private var state = "STOPPED"
    private fun serve(socket: Socket) {
        socket.soTimeout = 12000
        val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        val first = input.readLine() ?: return
        val headers = linkedMapOf<String, String>()
        while (true) { val line = input.readLine() ?: return; if (line.isEmpty()) break; headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
        var action = ""
        val text = if (first.startsWith("GET /dmr.xml")) {
            """<root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceType>${DlnaProtocol.MEDIA_RENDERER}</deviceType><friendlyName>VAP test TV</friendlyName><manufacturer>VAP</manufacturer><UDN>uuid:vap-renderer</UDN><serviceList>${listOf(DlnaProtocol.AV_TRANSPORT, DlnaProtocol.RENDERING_CONTROL, DlnaProtocol.CONNECTION_MANAGER).joinToString("") { "<service><serviceType>$it</serviceType><controlURL>/control</controlURL></service>" }}</serviceList></device></root>"""
        } else {
            val length = headers["content-length"]!!.toInt(); require(length <= 2 * 1024 * 1024)
            val chars = CharArray(length); var read = 0
            while (read < length) { val n = input.read(chars, read, length - read); check(n > 0); read += n }
            val values = DlnaProtocol.parseSoap(String(chars))
            action = headers["soapaction"]!!.trim('"').substringAfter('#'); actions += action
            val result = when (action) {
                "GetProtocolInfo" -> "<Sink>http-get:*:video/mp4:*,http-get:*:application/x-subrip:*</Sink>"
                "SetAVTransportURI" -> {
                    mediaUrl = values["CurrentURI"]!!; metadata = values["CurrentURIMetaData"]!!
                    OkHttpClient().newCall(Request.Builder().url(mediaUrl).header("Range", "bytes=0-511").build()).execute().use { check(it.code == 206); bytes = it.body!!.bytes() }; ""
                }
                "GetTransportInfo" -> "<CurrentTransportState>$state</CurrentTransportState>"
                "GetPositionInfo" -> "<TrackDuration>0:02:00</TrackDuration><RelTime>${DlnaProtocol.formatTime(position)}</RelTime>"
                "Play" -> { state = "PLAYING"; "" }
                "Pause" -> { state = "PAUSED_PLAYBACK"; "" }
                "Stop" -> { state = "STOPPED"; "" }
                "Seek" -> { position = DlnaProtocol.parseTime(values["Target"])!!; "" }
                "SetVolume" -> { volume = values["DesiredVolume"]!!.toInt(); "" }
                else -> ""
            }
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:${action}Response xmlns:u="${headers["soapaction"]!!.trim('"').substringBefore('#')}">$result</u:${action}Response></s:Body></s:Envelope>"""
        }
        val data = text.toByteArray()
        socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=utf-8\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(data); flush() }
    }
    override fun close() { server.close(); pool.shutdownNow(); thread.join(1000) }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun Instrumentation.verifyHlsProxy(player: MediaController) {
    val files = File(targetContext.filesDir, "network-fixtures")
    val upstream = NetworkFixtureServer(files, requireAuth = false)
    val http = OkHttpClient()
    val proxy = com.phnem.vap.cast.proxy.CastProxyServer(http)
    try {
        val base = "http://127.0.0.1:${proxy.ensureStarted(InetAddress.getByName("127.0.0.1"))}"
        proxy.publicBase = base
        val token = proxy.openSession(com.phnem.vap.cast.proxy.ProxyUpstream("${upstream.base}/protected/hls/stream.m3u8", emptyMap()), com.phnem.vap.cast.proxy.ProxyKind.HLS)
        val url = base + proxy.mainPath(token, com.phnem.vap.cast.proxy.ProxyKind.HLS, "m3u8")
        val playlist = http.newCall(Request.Builder().url(url).build()).execute().use { check(it.isSuccessful); check(it.header("Access-Control-Allow-Origin") == "*"); it.body!!.string() }
        val child = playlist.lineSequence().first { it.isNotBlank() && !it.startsWith('#') }
        check(child.startsWith(base) && !playlist.contains(upstream.base))
        val segment = File(files, "hls").listFiles()!!.filter { it.extension == "ts" }.minBy { it.name }
        http.newCall(Request.Builder().url(child).build()).execute().use { check(it.body!!.bytes().contentEquals(segment.readBytes())) }
        runOnMainSync { player.setMediaItem(PlayableItem(url, "HLS proxy fixture", "application/x-mpegURL").toMediaItem()); player.prepare(); player.play() }
        awaitUi("HLS proxy actual decode") { uiRead { player.playbackState == Player.STATE_READY && player.currentPosition > 500 && player.videoSize.width > 0 } }
        if (findUiNode { it.text?.toString() == "HLS proxy fixture" } == null) {
            val shot = uiAutomation.takeScreenshot(); uiTap(shot.width * .05f, shot.height * .4f); shot.recycle()
        }
        labelNode("HLS proxy fixture")
        runOnMainSync { player.pause(); player.seekTo(15_000) }
        awaitUi("HLS proxy seek") { uiRead { player.playbackState == Player.STATE_READY && player.currentPosition in 14_900..15_100 } }
    } finally { runOnMainSync { player.pause(); player.stop() }; proxy.closeAll(); upstream.close() }
}
