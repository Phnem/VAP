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
import com.phnem.vap.player.subtitles.online.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

fun Instrumentation.runOnlineSubtitleTests() {
    val namespace = "provider_test_${SystemClock.elapsedRealtime()}"
    val vault = CredentialVault(targetContext, namespace)
    val encrypted = targetContext.getSharedPreferences(namespace, 0)
    val cache = File(targetContext.cacheDir, namespace)
    var failure: Throwable? = null
    var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var player: MediaController? = null; var activity: PlayerActivity? = null
    try {
        runBlocking {
            vault.save(mapOf("test.secret" to "fake-private-value"))
            val first = encrypted.getString("test.secret", null)
            check(first != null && "fake-private-value" !in first)
            check(CredentialVault(targetContext, namespace).get("test.secret") == "fake-private-value")
            vault.save(mapOf("test.secret" to "fake-private-value"))
            check(encrypted.getString("test.secret", null) != first) { "GCM nonce was reused" }
            encrypted.edit().putString("test.other", encrypted.getString("test.secret", null)).commit()
            check(runCatching { vault.get("test.other") }.isFailure) { "Ciphertext was not bound to its field" }
        }
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        var searchCount = 0; var downloadCount = 0; var rejectHost = false; var badFile = false
        val subtitle = "1\n00:00:01,000 --> 00:00:03,000\nOnline VAP fixture\n"
        val gzip = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(subtitle.toByteArray()) } }.toByteArray()
        val transport = BoundedHttp { url, method, headers, body, limit ->
            requests += url to headers.toMap()
            when {
                "/login" in url -> {
                    check(method == "POST" && JSONObject(String(body!!)).getString("password") == "fake-password")
                    HttpReply(200, JSONObject().put("token", "fake-token").put("base_url", if (rejectHost) "https://api.opensubtitles.com.evil.test" else "vip-api.opensubtitles.com").toString().toByteArray())
                }
                "/subtitles?" in url -> {
                    check(headers["Api-Key"] == "fake-api-key" && headers["User-Agent"]!!.startsWith("VAP") && limit == 1024 * 1024)
                    searchCount++
                    if (searchCount == 1) HttpReply(429, ByteArray(0)) else HttpReply(200, """{"data":[{"attributes":{"language":"en","release":"VAP fixture","download_count":7,"moviehash_match":true,"from_trusted":true,"files":[{"file_id":9912345678,"file_name":"fixture.srt"}]}}]}""".toByteArray())
                }
                "/download" in url && "api." in url -> {
                    check(method == "POST" && headers["Authorization"] == "Bearer fake-token")
                    check(JSONObject(String(body!!)).getString("sub_format") == "srt")
                    downloadCount++
                    HttpReply(200, """{"link":"https://dl.opensubtitles.com/vap-test","file_name":"test.srt","remaining":2}""".toByteArray())
                }
                else -> {
                    check(url == "https://dl.opensubtitles.com/vap-test" && headers["Api-Key"] == null && headers["Authorization"] == null)
                    check(limit == 5 * 1024 * 1024)
                    HttpReply(200, if (badFile) "<html>error</html>".toByteArray() else gzip)
                }
            }
        }
        val client = OpenSubtitlesClient(targetContext, vault, transport, directory = cache)
        runBlocking {
            client.saveKey("fake-api-key")
            check(client.configured() && !client.signedIn())
            client.login("fake-user", "fake-password")
            check(client.signedIn())
            val search = SubtitleSearch("VAP contract", "en", 1, 2, "0123456789abcdef")
            val result = client.search(search)
            check(searchCount == 2 && result.single().hashMatch && result.single().fileId == 9912345678L)
            check(client.search(search) == result && searchCount == 2) { "Search cache missed" }
            val downloaded = client.download(result.single())
            check(downloaded.remaining == 2 && downloaded.file.readText() == subtitle)
            check(client.download(result.single()).file == downloaded.file && downloadCount == 1) { "Cached file consumed quota again" }
            badFile = true
            check(runCatching { client.download(result.single().copy(fileId = 9912345679)) }.isFailure)
            check(!File(cache, "file-9912345679.srt").exists())
            rejectHost = true
            check(runCatching { client.login("fake-user", "fake-password") }.isFailure) { "Untrusted login host accepted" }
            client.clear(); check(!client.configured() && !client.signedIn())
        }

        val uri = android.net.Uri.fromFile(File(targetContext.filesDir, "subtitle-fixtures/black.mp4")).toString()
        runOnMainSync { future = PlaybackConnection.connect(targetContext) }; player = future!!.get(15, TimeUnit.SECONDS)
        val controller = player!!
        runOnMainSync { controller.setMediaItem(PlayableItem(uri, "VAP online UI fixture").toMediaItem()); controller.prepare(); controller.pause() }
        awaitUi("online UI player ready") { uiRead { controller.playbackState == Player.STATE_READY } }
        val monitor = addMonitor(PlayerActivity::class.java.name, null, false)
        uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n com.phnem.vap/.player.PlayerActivity -a ${PlaybackService.ACTION_RESUME}").close()
        activity = waitForMonitorWithTimeout(monitor, 10000) as? PlayerActivity; removeMonitor(monitor); check(activity != null)
        val screen = uiAutomation.takeScreenshot(); val w = screen.width.toFloat(); val h = screen.height.toFloat(); screen.recycle()
        if (findUiNode { it.contentDescription?.toString() in listOf("Subtitles", "Субтитры") } == null) { uiTap(w * .05f, h * .4f); SystemClock.sleep(400) }
        clickUi(awaitNode { it.contentDescription?.toString() in listOf("Subtitles", "Субтитры") })
        labelNode(targetContext.getString(R.string.sub_tools)) // шторка выезжает: сначала дожидаемся её, потом ищем вкладки
        if (findUiNode { it.text?.toString() == targetContext.getString(R.string.os_online) } == null) uiDrag(w * .65f, h * .23f, w * .25f, h * .23f)
        clickUi(labelNode(targetContext.getString(R.string.os_online)))
        labelNode(targetContext.getString(R.string.os_query))
        clickUi(labelNode(targetContext.getString(R.string.os_account)))
        labelNode(targetContext.getString(R.string.os_api_key)); uiScreenshot("online-subtitle-account.png")
    } catch (e: Throwable) { failure = e; runCatching { uiScreenshot("online-subtitle-failure.png") } }
    finally {
        runOnMainSync { player?.pause(); activity?.finish(); future?.let { MediaController.releaseFuture(it) } }
        encrypted.edit().clear().commit()
        cache.listFiles().orEmpty().forEach { it.delete() }; cache.delete()
    }
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
        putString("stream", if (failure == null) "PASS: Android Keystore encryption/nonce/AAD, API contract/rate retry/cache/login, no CDN credential leakage, gzip and invalid file handling, native Online/account UI; live account not tested\n" else "FAIL: ${android.util.Log.getStackTraceString(failure)}")
    })
}
