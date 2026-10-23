package com.phnem.vap

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.phnem.vap.network.*
import com.phnem.vap.player.*
import com.phnem.vap.player.model.PlayableItem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Instrumentation.runServerDeviceTests() {
    val requests = CopyOnWriteArrayList<Triple<String, String, String>>()
    val secret = "fake-server-token"
    var badSource = false
    val transport = BoundedHttp { url, method, headers, body, limit ->
        check(limit == 2 * 1024 * 1024 && headers["Accept"] == "application/json")
        requests += Triple(url, method, body?.toString(Charsets.UTF_8).orEmpty())
        val path = java.net.URI(url).path
        val json = when {
            path.endsWith("/Users/AuthenticateByName") -> {
                check(method == "POST" && JSONObject(String(body!!)).getString("Pw") == "fake-password")
                check(headers["X-Emby-Authorization"]!!.contains("Client=\"VAP\""))
                """{"User":{"Id":"user1"},"AccessToken":"$secret"}"""
            }
            path.endsWith("/Users/Me") -> """{"Id":"user1"}"""
            path.endsWith("/Views") -> """{"Items":[{"Id":"library1","Name":"Movies","Type":"CollectionFolder","CollectionType":"movies"}]}"""
            path.endsWith("/Items") -> """{"Items":[{"Id":"movie1","Name":"VAP server movie","Type":"Movie","UserData":{"PlaybackPositionTicks":25000000}}],"TotalRecordCount":1}"""
            path.endsWith("/PlaybackInfo") -> """{"PlaySessionId":"session1","MediaSources":[{"Id":"source1","Container":"mp4","SupportsDirectPlay":true,"DirectStreamUrl":"${if (badSource) "https://evil.test/steal.mp4" else "/Videos/movie1/stream.mp4?api_key=$secret&Static=true"}"}]}"""
            path.endsWith("/library/sections") -> { check(headers["X-Plex-Token"] == secret); """{"MediaContainer":{"size":1,"Directory":[{"key":"1","type":"movie","title":"Movies"}]}}""" }
            path.endsWith("/library/sections/1") -> """{"MediaContainer":{"size":1,"Directory":[{"key":"all","type":"movie","title":"All movies"}]}}"""
            path.endsWith("/library/sections/1/all") -> """{"MediaContainer":{"size":1,"Metadata":[{"key":"/library/metadata/21","ratingKey":"21","type":"movie","title":"VAP Plex movie","viewOffset":3000}]}}"""
            path.endsWith("/library/metadata/21") -> """{"MediaContainer":{"Metadata":[{"Media":[{"container":"mp4","Part":[{"id":"part1","key":"/library/parts/part1/video.mp4?X-Plex-Token=$secret"}]}]}]}}"""
            "/Sessions/Playing" in path || path.endsWith("/:/timeline") -> return@BoundedHttp HttpReply(204, ByteArray(0))
            else -> error("Unexpected mock server path: $path")
        }
        HttpReply(200, json.toByteArray())
    }
    val client = ServerClient(transport)
    val store = NetworkConnectionStore(targetContext)
    var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var controller: MediaController? = null; var reporter: ServerProgress? = null; var activity: MainActivity? = null
    var savedConnection: String? = null; var failure: Throwable? = null
    try {
        verifyServerRefresh()
        var jellyfin: NetworkConnection? = null
        runBlocking {
            for (kind in listOf(NetworkKind.JELLYFIN, NetworkKind.EMBY)) {
                val initial = NetworkConnection(name = kind.name, kind = kind, url = "https://server.test/api/")
                val loggedIn = client.login(initial, "fake-user", "fake-password")
                check(loggedIn.password.isEmpty() && loggedIn.token == secret && loggedIn.userId == "user1")
                client.validate(loggedIn)
                val root = client.browse(loggedIn, loggedIn.rootUrl); check(root.single().folder)
                val videos = client.browse(loggedIn, root.single().url); check(videos.single().resumeMs == 2500L)
                val play = client.play(loggedIn, videos.single())
                check(play.mimeType == "video/mp4" && !play.uri.contains(secret) && play.uri.startsWith("https://server.test/api/Videos/") && play.server?.itemId == "movie1")
                client.progress(loggedIn, play.server!!, 2500, 120000, false, false, true)
                client.progress(loggedIn, play.server!!, 6000, 120000, true, false, false)
                client.progress(loggedIn, play.server!!, 7000, 120000, true, true, false)
                badSource = true; check(runCatching { client.play(loggedIn, videos.single()) }.isFailure); badSource = false
                if (kind == NetworkKind.JELLYFIN) jellyfin = loggedIn
            }
            val plex = NetworkConnection(name = "Plex", kind = NetworkKind.PLEX, url = "https://plex.test/api/", token = secret)
            client.validate(plex)
            val sections = client.browse(plex, plex.rootUrl); check(sections.single().folder)
            val pivots = client.browse(plex, sections.single().url); check(pivots.single().folder)
            val movies = client.browse(plex, pivots.single().url); check(movies.single().serverItemId == "21")
            val play = client.play(plex, movies.single()); check(play.startPositionMs == 3000L && !play.uri.contains(secret))
            client.progress(plex, play.server!!, 4000, 120000, false, false, true)
            check(requests.any { it.first.contains("/:/timeline") && it.first.contains("state=playing") })
        }
        val connection = jellyfin!!.copy(id = "vap-server-progress-test")
        runBlocking { store.save(connection) }; savedConnection = connection.id
        check(!targetContext.getSharedPreferences("network_connections", 0).getString("connections", "")!!.contains(secret))
        runOnMainSync { future = PlaybackConnection.connect(targetContext) }; controller = future!!.get(15, TimeUnit.SECONDS)
        var position = 1000L; var paused = false
        val item = PlayableItem("https://server.test/api/Videos/movie1/stream.mp4", "Progress fixture", "video/mp4", ServerPlaybackRef(connection.id, "movie1", "source1", "session1")).toMediaItem()
        val fake = object : ForwardingPlayer(controller!!) {
            override fun getCurrentMediaItem(): MediaItem = item
            override fun getCurrentPosition() = position
            override fun getDuration() = 120000L
            override fun getPlaybackState() = Player.STATE_READY
            override fun getPlayWhenReady() = !paused
        }
        val before = requests.size
        runOnMainSync { reporter = ServerProgress(targetContext, fake, client); reporter!!.sample() }
        awaitUi("server started report") { requests.size > before }
        position = 8000; paused = true
        runOnMainSync { reporter!!.sample(true) }
        awaitUi("server pause report") { requests.drop(before).any { it.first.contains("/Progress") && JSONObject(it.third).optLong("PositionTicks") == 80000000L && JSONObject(it.third).optBoolean("IsPaused") } }
        runOnMainSync { reporter!!.release(); reporter = null }
        awaitUi("server stopped report") { requests.drop(before).any { it.first.contains("/Stopped") } }
        targetContext.getSharedPreferences("vap_app", 0).edit().putBoolean("first_run_done", true).commit() // the first-run screen would cover the library under test
        val monitor = addMonitor(MainActivity::class.java.name, null, false)
        uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n com.phnem.vap/.MainActivity").close()
        activity = waitForMonitorWithTimeout(monitor, 10000) as? MainActivity; removeMonitor(monitor); check(activity != null)
        awaitUi("network menu after optional resume offer") {
            val later = findUiNode { it.text?.toString() == targetContext.getString(R.string.resume_session_later) }
            if (later != null) { runCatching { clickUi(later) }; false }
            else if (findUiNode { it.text?.toString() == targetContext.getString(R.string.network_sources) } != null) true
            else { findUiNode { it.text?.toString() == targetContext.getString(R.string.library_title) }?.let { runCatching { clickUi(it) } }; false }
        }
        clickUi(labelNode(targetContext.getString(R.string.network_sources)))
        clickUi(labelNode(targetContext.getString(R.string.network_add)))
        fun chip(label: String) {
            repeat(8) {
                val node = findUiNode { it.text?.toString() == label }; val bounds = android.graphics.Rect(); node?.getBoundsInScreen(bounds)
                if (node != null && bounds.width() > 30) { clickUi(node); return }
                val anchor = awaitNode { n -> val b = android.graphics.Rect(); n.getBoundsInScreen(b); n.text?.toString() in listOf("HTTP / HLS / DASH", "WEBDAV", "SMB", "FTP", "SFTP", "DLNA", "Jellyfin", "Emby", "Plex") && b.width() > 30 }
                val b = android.graphics.Rect(); anchor.getBoundsInScreen(b)
                val shot = uiAutomation.takeScreenshot(); val width = shot.width.toFloat(); shot.recycle()
                uiDrag(width * .86f, b.exactCenterY(), width * .2f, b.exactCenterY()); SystemClock.sleep(150)
            }
            error("Cannot reveal $label")
        }
        chip("Jellyfin"); labelNode(targetContext.getString(R.string.server_token)); labelNode(targetContext.getString(R.string.server_user_id))
        uiScreenshot("server-jellyfin-form.png")
        chip("Plex"); labelNode(targetContext.getString(R.string.server_plex_auth)); uiScreenshot("server-plex-form.png")
    } catch (e: Throwable) { failure = e; runCatching { uiScreenshot("server-failure.png") } }
    finally {
        runOnMainSync { reporter?.release(); activity?.finish(); future?.let { MediaController.releaseFuture(it) } }
        savedConnection?.let { runBlocking { store.remove(it) } }
    }
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
        putString("stream", if (failure == null) "PASS: Jellyfin/Emby login/library/source/resume/progress contracts, Plex sections/keys/parts/timeline, token-free scoped URLs, encrypted token, progress lifecycle, deferred cold-session source refresh/decode/seek/cancel/error and native account forms; live servers not tested\n" else "FAIL: ${android.util.Log.getStackTraceString(failure)}")
    })
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun Instrumentation.verifyServerRefresh() {
    val calls = AtomicInteger(); val children = AtomicInteger()
    val oldRef = ServerPlaybackRef("fixture", "movie1", sessionId = "old")
    val restored = ServerPlaybackRef.parse(oldRef.json(), forceRefresh = true)!!
    check(restored.refresh && !oldRef.refresh)
    val old = PlayableItem("https://expired.test/old.m3u8", "Restored", "application/x-mpegURL", restored).toMediaItem()
    val fresh = PlayableItem(android.net.Uri.fromFile(java.io.File(targetContext.filesDir, "vap-test.mp4")).toString(), "Restored", "video/mp4", oldRef.copy(sessionId = "fresh")).toMediaItem()
    val factory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(targetContext)
    fun child(item: MediaItem): androidx.media3.exoplayer.source.MediaSource { children.incrementAndGet(); return factory.createMediaSource(item) }
    var player: androidx.media3.exoplayer.ExoPlayer? = null
    try {
        val source = ServerMediaSource(old, { calls.incrementAndGet(); delay(50); fresh }, ::child)
        check(calls.get() == 0) { "Resolving before preparation" }
        runOnMainSync {
            player = androidx.media3.exoplayer.ExoPlayer.Builder(targetContext).build().apply {
                setMediaSource(source); seekTo(12_000); prepare()
            }
        }
        try { awaitUi("refreshed server source ready") { uiRead { player!!.playbackState == Player.STATE_READY } } }
        catch(e: Exception) { throw IllegalStateException("Refresh state=${uiRead { player!!.playbackState }}, calls=${calls.get()}, children=${children.get()}, error=${uiRead { player!!.playerError }}", e) }
        check(calls.get() == 1 && children.get() == 1)
        check(uiRead { player!!.currentPosition in 11_900..12_100 && player!!.currentMediaItem?.localConfiguration?.uri == fresh.localConfiguration?.uri })
        check(uiRead { ServerPlaybackRef.parse(player!!.currentMediaItem?.mediaMetadata?.extras?.getString(ServerPlaybackRef.EXTRA))?.sessionId == "fresh" })
        runOnMainSync { player!!.release(); player = null }
        // Release while resolution is suspended: no child may be prepared afterwards.
        val cancelled = ServerMediaSource(old, { calls.incrementAndGet(); delay(500); fresh }, ::child)
        runOnMainSync { player = androidx.media3.exoplayer.ExoPlayer.Builder(targetContext).build().apply { setMediaSource(cancelled); prepare() } }
        awaitUi("server resolve started") { calls.get() == 2 }
        runOnMainSync { player!!.release(); player = null }
        SystemClock.sleep(650); check(children.get() == 1)
        val failed = ServerMediaSource(old, { throw NetworkAuthFailure() }, ::child)
        runOnMainSync { player = androidx.media3.exoplayer.ExoPlayer.Builder(targetContext).build().apply { setMediaSource(failed); prepare() } }
        awaitUi("server auth refresh error") { uiRead { player!!.playerError != null } }
        check(uiRead { NetworkFailures.permanent(player!!.playerError!!) })
    } finally { runOnMainSync { player?.release() } }
}
