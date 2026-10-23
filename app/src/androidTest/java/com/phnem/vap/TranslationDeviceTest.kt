package com.phnem.vap

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.phnem.vap.ai.translation.*
import com.phnem.vap.ai.whisper.AiRuntime
import com.phnem.vap.network.*
import com.phnem.vap.player.PlaybackConnection
import com.phnem.vap.player.settings.PlayerPreferences
import com.phnem.vap.player.subtitles.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Instrumentation.runTranslationDeviceTests() {
    val report = Bundle(); var failure: Throwable? = null
    val runtime = TranslationRuntime.get(targetContext)
    val ai = AiRuntime.get(targetContext)
    val fixture = File(targetContext.filesDir, "translation-${SystemClock.elapsedRealtime()}.mp4")
    var connection: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var player: MediaController? = null
    var activity: Activity? = null
    var previousItems = emptyList<MediaItem>(); var previousIndex = 0; var previousPosition = 0L
    val uri = Uri.fromFile(fixture).toString()
    val preferences = PlayerPreferences(targetContext)
    var original: SubtitleSource? = null; var output: SubtitleSource? = null
    try {
        uiAutomation.executeShellCommand("am start -W -n com.phnem.vap/.MainActivity").let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() } }
        for (model in LocalTranslationModel.entries) {
            val from = File(targetContext.filesDir, "translation-fixtures/${model.id}")
            check(from.isDirectory)
            val to = runtime.models.directory(model).apply { mkdirs() }
            for (name in listOf("config.json", "model.bin", "shared_vocabulary.json", "source.spm", "target.spm")) File(from, name).copyTo(File(to, name), overwrite = true)
            check(runtime.models.ready(model))
        }
        runBlocking {
            val started = SystemClock.elapsedRealtime()
            val ru = withTimeout(90_000) { runtime.engine.translate(runtime.models.directory(LocalTranslationModel.EN_RU), listOf("Hello, world!", "I like books.")) }
            check(ru[0].contains("мир", true) && ru[1].contains("книг", true)) { "Actual EN-RU: $ru" }
            val en = withTimeout(90_000) { runtime.engine.translate(runtime.models.directory(LocalTranslationModel.RU_EN), listOf("Привет, мир!", "Я люблю книги.")) }
            check(en[0].contains("world", true) && en[1].contains("book", true)) { "Actual RU-EN: $en" }
            report.putLong("translateMs", SystemClock.elapsedRealtime() - started)
            uiMark("actual EN-RU / RU-EN decoded")
            val cancelled = launch { runtime.engine.translate(runtime.models.directory(LocalTranslationModel.EN_RU), List(200) { "This is a long test of cancellation." }) }
            delay(300); cancelled.cancelAndJoin()
            check(withTimeout(90_000) { runtime.engine.translate(runtime.models.directory(LocalTranslationModel.EN_RU), listOf("Good morning!")) }.first().any { it in 'А'..'я' })
            uiMark("native translation cancellation/retry")
            var calls = 0
            var mode = "ok"
            val cloud = CloudTranslator(BoundedHttp { url, method, headers, body, limit ->
                calls++; check(url == "https://translation.invalid/v1/chat/completions" && method == "POST" && limit <= 1_048_576)
                check(headers["Authorization"] == "Bearer fake-test-key")
                val request = JSONObject(String(body!!)); check(request.getString("model") == "test/model")
                check(request.getJSONArray("messages").getJSONObject(1).getString("content").contains("Hello"))
                if (mode == "redirect") HttpReply(302, ByteArray(0), location = "https://other.invalid/")
                else {
                    val rows = JSONArray().put(JSONObject().put("id", if (mode == "duplicate") 0 else 1).put("text", "Мир 🙂"))
                        .put(JSONObject().put("id", 0).put("text", "Привет"))
                    val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", if (mode == "length") "length" else "stop")
                        .put("message", JSONObject().put("content", JSONObject().put("translations", rows).toString()))))
                    HttpReply(200, response.toString().toByteArray())
                }
            })
            val config = TranslationCloudConfig("https://translation.invalid/v1/chat/completions", "fake-test-key", "test/model")
            check(cloud.translate(config, listOf("Hello", "World"), "en", "ru", mapOf("World" to "Мир"), true) == listOf("Привет", "Мир 🙂"))
            for (bad in listOf("duplicate", "redirect", "length")) {
                mode = bad; val before = calls
                check(runCatching { cloud.translate(config, listOf("Hello", "World"), "en", "ru", emptyMap(), false) }.isFailure)
                check(calls == before + 1)
            }
        }
        File(targetContext.filesDir, "vap-test.mp4").copyTo(fixture)
        val lines = listOf(SubtitleLine(1000, 2000, "Hello, world!"), SubtitleLine(2500, 3500, "Alice likes books."), SubtitleLine(4000, 5000, "Good morning!"))
        original = runBlocking { SubtitleStorage(targetContext).create("Translation English fixture", "en", lines) }
        runBlocking { preferences.setSubtitles(uri, SubtitleSettings(sources = listOf(original!!), selection = original!!.id)) }
        runOnMainSync { connection = PlaybackConnection.connect(targetContext) }
        player = connection!!.get(15, TimeUnit.SECONDS)
        runOnMainSync {
            previousItems = (0 until player!!.mediaItemCount).map(player!!::getMediaItemAt)
            previousIndex = player!!.currentMediaItemIndex.coerceAtLeast(0); previousPosition = player!!.currentPosition
            player!!.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(uri).setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle("Translation fixture").build()).build())
            player!!.prepare(); player!!.pause()
        }
        awaitUi("translation fixture ready") { uiRead { player!!.playbackState == Player.STATE_READY } }
        val monitor = addMonitor("com.phnem.vap.player.PlayerActivity", null, false)
        uiAutomation.executeShellCommand("am start -W -f 0x18000000 -n com.phnem.vap/.player.PlayerActivity -a ${com.phnem.vap.player.PlaybackService.ACTION_RESUME}").let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() } }
        activity = waitForMonitorWithTimeout(monitor, 10_000); removeMonitor(monitor)
        awaitUi("translation foreground") { uiRead { activity?.hasWindowFocus() == true } }
        runOnMainSync { runtime.translate(uri, original!!, "en", "ru", "Alice=Алиса", true, false) }
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (ai.state.value.running && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        check(!ai.state.value.running && ai.state.value.error == null) { "Translation task: ${ai.state.value}" }
        output = runtime.result.value.source ?: error("No translated track")
        val translated = runBlocking { SubtitleStorage(targetContext).read(output!!.id) }
        check(translated.map { it.startMs to it.endMs } == lines.map { it.startMs to it.endMs })
        check(translated[0].text.contains("мир", true) && translated[1].text.contains("Алиса") && !translated[1].text.contains("Alice"))
        val settings = runBlocking { preferences.fileSettings(uri).first().subtitles }
        check(settings.selection == output!!.id && settings.secondary == original!!.id)
        check(SubtitleDocument.parse(SubtitleDocument.export(translated, true)) == translated)
        runOnMainSync { runtime.translate(uri, original!!, "en", "ru", "Alice=Алиса", true, false) }
        val cacheDeadline = SystemClock.elapsedRealtime() + 10_000
        while (ai.state.value.running && SystemClock.elapsedRealtime() < cacheDeadline) SystemClock.sleep(50)
        check(!ai.state.value.running && runtime.result.value.source?.id == output!!.id)
        if (findUiNode { it.contentDescription?.toString() in listOf("Subtitles", "Субтитры") } == null) {
            val shot = uiAutomation.takeScreenshot(); uiTap(shot.width * .05f, shot.height * .4f); shot.recycle()
        }
        clickUi(awaitNode { it.contentDescription?.toString() in listOf("Subtitles", "Субтитры") })
        clickUi(labelNode(targetContext.getString(R.string.tr_title)))
        labelNode("Translation English fixture")
        labelNode(targetContext.getString(R.string.tr_local)); uiScreenshot("translation-panel.png")
        clickUi(labelNode(targetContext.getString(R.string.tr_cloud)))
        repeat(8) {
            if (findUiNode { it.text?.toString() == targetContext.getString(R.string.tr_key) } == null) {
                val shot = uiAutomation.takeScreenshot(); uiDrag(shot.width * .7f, shot.height * .8f, shot.width * .7f, shot.height * .35f); shot.recycle()
            }
        }
        labelNode(targetContext.getString(R.string.tr_key)); uiScreenshot("translation-cloud.png")
        report.putString("stream", "PASS: actual CPU EN-RU/RU-EN, Cyrillic UTF-8, native cancellation/retry, strict mock cloud IDs/auth/redirect/no-retry, local glossary, preserved timecodes, separate translated track, dual subtitles, cached retry and ordinary UI; 4 phrases both directions=${report.getLong("translateMs")}ms\n")
    } catch (error: Throwable) { failure = error; report.putString("stream", "FAIL: ${android.util.Log.getStackTraceString(error)}\n") }
    finally {
        runOnMainSync { ai.cancel(); activity?.finish() }
        runBlocking { preferences.resetFile(uri); original?.let { SubtitleStorage(targetContext).delete(it.id) }; output?.let { SubtitleStorage(targetContext).delete(it.id) } }
        runOnMainSync {
            player?.apply { if (previousItems.isNotEmpty()) { setMediaItems(previousItems, previousIndex, previousPosition); prepare(); pause() } }
            connection?.let(MediaController::releaseFuture)
        }
        fixture.delete()
    }
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
}
