package com.phnem.vap

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.phnem.vap.player.*
import com.phnem.vap.player.model.PlayableItem
import com.phnem.vap.player.settings.PlayerPreferences
import com.phnem.vap.player.subtitles.*
import com.phnem.vap.player.ui.PlayerOrientation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Instrumentation.runSubtitleDeviceTests() {
    val prefs = PlayerPreferences(targetContext)
    val directory = File(targetContext.filesDir, "subtitle-fixtures")
    val uri = Uri.fromFile(File(directory, "embedded.mkv")).toString()
    val original = runBlocking { prefs.settings.first() }; val saved = runBlocking { prefs.fileSettings(uri).first() }
    var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var player: MediaController? = null; var activity: PlayerActivity? = null; var failure: Throwable? = null
    val storage = SubtitleStorage(targetContext)
    try {
        uiMark("embedded subtitle decoding, language and persistent selection")
        val ass = File(directory, "basic.ass").apply { writeText("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,{\\i1}ASS Android\\NРУ") }
        val assCopy = runBlocking { storage.import(Uri.fromFile(ass)) }
        check(runBlocking { storage.read(assCopy.id) }.single().text == "ASS Android\nРУ")
        val extracted = runBlocking { EmbeddedSubtitleExtractor.extract(targetContext, Uri.parse(uri)) }
        check(extracted.size == 2 && extracted.all { it.lines.size == 2 }) { "Unexpected extracted tracks: $extracted" }
        // FFprobe confirms the remuxed fixture has 23 ms AAC preroll: PTS 1.023, duration 3.0.
        check(extracted.flatMap { it.lines }.any { it.text == "Субтитры РУ" && it.startMs == 1023L && it.endMs == 4023L }) { "Timing differs from ffprobe: $extracted" }
        runBlocking {
            prefs.updateSettings { it.copy(orientation = PlayerOrientation.LANDSCAPE, autoNext = false) }
            prefs.setSubtitles(uri, SubtitleSettings(language = "ru", discovered = true))
        }
        runOnMainSync { future = PlaybackConnection.connect(targetContext) }
        player = future!!.get(15, TimeUnit.SECONDS); val controller = player!!
        runOnMainSync { controller.setMediaItem(PlayableItem(uri, "embedded.mkv").toMediaItem()); controller.prepare(); controller.seekTo(2000); controller.play() }
        awaitUi("embedded tracks and Russian cue") {
            uiRead { controller.currentTracks.subtitleChoices().size == 2 && controller.currentCues.cues.any { it.text?.contains("Субтитры РУ") == true } }
        }
        runOnMainSync { controller.pause(); controller.seekTo(2000) }
        val english = uiRead { controller.currentTracks.subtitleChoices().firstOrNull { "|en|" in it.key } }
            ?: error("No English subtitle track")
        runBlocking { prefs.setSubtitles(uri, SubtitleSettings(selection = english.key, discovered = true)) }
        runOnMainSync { controller.seekTo(2000); controller.play() }
        awaitUi("English selected by stored key") { uiRead { controller.currentCues.cues.any { it.text?.contains("VAP subtitle EN") == true } } }
        runOnMainSync { controller.pause(); controller.seekTo(2000) }
        runBlocking { prefs.setSubtitles(uri, SubtitleSettings(selection = "off", discovered = true)) }
        awaitUi("embedded subtitles disabled") { uiRead { controller.currentCues.cues.isEmpty() } }

        uiMark("external timeline and full native UI")
        val importedEnglish = runBlocking { storage.import(Uri.fromFile(File(directory, "english.srt"))) }
        val importedRussian = runBlocking { storage.import(Uri.fromFile(File(directory, "russian.srt"))) }
        val base = SubtitleSettings(selection = importedEnglish.id, secondary = importedRussian.id,
            sources = listOf(importedEnglish, importedRussian), discovered = true)
        runBlocking { prefs.setSubtitles(uri, base) }
        val monitor = addMonitor(PlayerActivity::class.java.name, null, false)
        uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n com.phnem.vap/.player.PlayerActivity -a ${PlaybackService.ACTION_RESUME}").close()
        activity = waitForMonitorWithTimeout(monitor, 10000) as? PlayerActivity; removeMonitor(monitor); check(activity != null)
        awaitUi("dual language overlay") { findUiNode { it.contentDescription?.toString()?.let { t -> "VAP subtitle EN" in t && "Субтитры РУ" in t } == true } != null }
        uiScreenshot("subtitle-dual.png")
        runBlocking { prefs.setSubtitles(uri, base.copy(delayMs = 3000)) }
        awaitUi("delay hides future cue") { findUiNode { it.contentDescription?.toString()?.contains("VAP subtitle EN") == true } == null }
        runOnMainSync { controller.seekTo(5000) }
        awaitUi("delay shows both shifted cues") { findUiNode { it.contentDescription?.toString()?.let { t -> "VAP subtitle EN" in t && "Субтитры РУ" in t } == true } != null }
        runBlocking { prefs.setSubtitles(uri, base) }
        runOnMainSync { controller.seekTo(2000) }
        val shot = uiAutomation.takeScreenshot(); val w = shot.width.toFloat(); val h = shot.height.toFloat(); shot.recycle()
        fun reveal(label: String): android.view.accessibility.AccessibilityNodeInfo {
            repeat(12) {
                findUiNode { it.text?.toString() == label || it.contentDescription?.toString() == label }?.let { node ->
                    val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
                    if (bounds.height() > 25) return node
                }
                uiDrag(w * .8f, h * .84f, w * .8f, h * .45f); SystemClock.sleep(180)
            }
            error("Not visible: $label")
        }
        if (findUiNode { it.contentDescription?.toString() in listOf("Subtitles", "Субтитры") } == null) { uiTap(w * .05f, h * .4f); SystemClock.sleep(400) }
        clickUi(awaitNode { it.contentDescription?.toString() in listOf("Subtitles", "Субтитры") })
        labelNode(targetContext.getString(R.string.sub_tools))
        clickUi(labelNode(targetContext.getString(R.string.sub_style))); uiScreenshot("subtitle-style.png")
        clickUi(labelNode(targetContext.getString(R.string.sub_timing))); labelNode(targetContext.getString(R.string.sub_delay))
        clickUi(labelNode(targetContext.getString(R.string.sub_text)))
        labelNode("${SubtitleDocument.timestamp(1000)}  VAP subtitle EN")
        clickUi(reveal("${SubtitleDocument.timestamp(5000)}  Second English cue"))
        awaitUi("transcript cue seek") { uiRead { controller.currentPosition in 4900..5100 } }
        clickUi(labelNode(targetContext.getString(R.string.sub_tracks)))
        // Select a real public text document using SAF, then verify the imported private copy.
        repeat(8) {
            if (findUiNode { it.text?.toString() == targetContext.getString(R.string.sub_import) } != null) return@repeat
            uiDrag(w * .8f, h * .72f, w * .8f, h * .55f); SystemClock.sleep(150)
        }
        clickUi(labelNode(targetContext.getString(R.string.sub_import)))
        awaitNode { it.packageName?.toString()?.contains("documentsui") == true }
        SystemClock.sleep(500)
        val pickerShot = uiAutomation.takeScreenshot(); val pickerW = pickerShot.width.toFloat(); val pickerH = pickerShot.height.toFloat(); pickerShot.recycle()
        if (findUiNode { it.text?.toString() == "VAP-stage62.srt" } == null) {
            clickUi(awaitNode { it.contentDescription?.toString() in listOf("Show roots", "Показать корневые папки") })
            var opened = false
            repeat(8) {
                if (!opened) {
                    val node = findUiNode { it.text?.toString() in listOf("Downloads", "Загрузки") }
                    val bounds = android.graphics.Rect(); node?.getBoundsInScreen(bounds)
                    if (node != null && bounds.height() > 20 && bounds.bottom <= pickerH) { clickUi(node); opened = true }
                    else { uiDrag(pickerW * .15f, pickerH * .8f, pickerW * .15f, pickerH * .35f); SystemClock.sleep(200) }
                }
            }
            check(opened) { "Downloads root not visible" }
        }
        clickUi(labelNode("VAP-stage62.srt"))
        awaitUi("SAF subtitle imported") { runBlocking { prefs.fileSettings(uri).first() }.subtitles.sources.any { it.title == "VAP-stage62.srt" } }
        val persisted = runBlocking { PlayerPreferences(targetContext).fileSettings(uri).first() }.subtitles
        check(runBlocking { storage.read(persisted.selection) }.first().text == "VAP subtitle EN")
        uiScreenshot("subtitle-imported.png")
        clickUi(labelNode(targetContext.getString(R.string.sub_text)))
        // В альбомной ориентации кнопка правки первой реплики ниже поля поиска: список прокручивается.
        repeat(8) {
            val edit = findUiNode { it.text?.toString() == targetContext.getString(R.string.sub_edit) }
            val bounds = Rect().also { edit?.getBoundsInScreen(it) }
            if (edit != null && bounds.bottom < h * .88f && bounds.height() > 40) return@repeat
            uiDrag(w * .8f, h * .72f, w * .8f, h * .55f); SystemClock.sleep(250)
        }
        clickUi(labelNode(targetContext.getString(R.string.sub_edit)))
        val field = awaitNode { it.isEditable && it.text?.toString() == "VAP subtitle EN" }
        check(field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Edited VAP subtitle")
        }))
        clickUi(labelNode(targetContext.getString(R.string.sub_save)))
        awaitUi("edited text persisted") { runBlocking { storage.read(persisted.selection) }.first().text == "Edited VAP subtitle" }
        labelNode("${SubtitleDocument.timestamp(1000)}  Edited VAP subtitle")
        clickUi(labelNode("SRT ↓"))
        awaitNode { it.packageName?.toString()?.contains("documentsui") == true }
        val exportName = "VAP-stage62-export-${System.currentTimeMillis()}.srt"
        val nameField = awaitNode { it.isEditable && it.text?.toString()?.contains("subtitles") == true }
        check(nameField.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, exportName)
        }))
        clickUi(awaitNode { it.isEnabled && it.text?.toString() in listOf("Save", "SAVE", "Сохранить", "СОХРАНИТЬ") })
        awaitUi("export picker closed") { uiAutomation.rootInActiveWindow?.packageName?.toString() == targetContext.packageName }
        awaitUi("SAF export contains edited text") {
            runCatching {
                val text = uiAutomation.executeShellCommand("cat /sdcard/Download/$exportName").use { fd ->
                    java.io.FileInputStream(fd.fileDescriptor).use { String(it.readBytes(), Charsets.UTF_8) }
                }
                SubtitleDocument.parse(text).first().text == "Edited VAP subtitle"
            }.getOrDefault(false)
        }
        clickUi(labelNode(targetContext.getString(R.string.sub_tracks)))
        clickUi(reveal(targetContext.getString(R.string.sub_find_siblings)))
        awaitNode { it.packageName?.toString()?.contains("documentsui") == true }
        if (findUiNode { it.text?.toString() == "VAP-Subtitle-Test" } == null) {
            val roots = findUiNode { it.contentDescription?.toString() in listOf("Show roots", "Показать корневые папки") }
            if (roots == null) clickUi(labelNode("Download")) else {
            clickUi(roots)
            val pickerShot = uiAutomation.takeScreenshot(); val pw = pickerShot.width.toFloat(); val ph = pickerShot.height.toFloat(); pickerShot.recycle()
            var opened = false
            repeat(8) {
                if (!opened) {
                    val node = findUiNode { it.text?.toString() in listOf("Downloads", "Загрузки") }
                    val bounds = android.graphics.Rect(); node?.getBoundsInScreen(bounds)
                    if (node != null && bounds.height() > 20 && bounds.bottom <= ph) { clickUi(node); opened = true }
                    else { uiDrag(pw * .15f, ph * .8f, pw * .15f, ph * .35f); SystemClock.sleep(200) }
                }
            }
            check(opened)
            }
        }
        clickUi(labelNode("VAP-Subtitle-Test"))
        clickUi(awaitNode { it.text?.toString()?.lowercase() in listOf("use this folder", "использовать эту папку") && it.isEnabled })
        clickUi(awaitNode { it.text?.toString()?.lowercase() in listOf("allow", "разрешить") && it.isEnabled })
        awaitUi("sibling subtitle imported using folder grant") { runBlocking { prefs.fileSettings(uri).first() }.subtitles.sources.any { it.title == "embedded.ru.srt" } }
        val tree = targetContext.contentResolver.persistedUriPermissions.first { it.isReadPermission && "VAP-Subtitle-Test" in it.uri.toString() }.uri
        val doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree) + "/embedded.mkv")
        check(runBlocking { storage.discover(doc) }.size == 1)
        runBlocking { prefs.setSubtitles(doc.toString(), SubtitleSettings()) }
        runOnMainSync { controller.setMediaItem(PlayableItem(doc.toString(), "embedded.mkv").toMediaItem()); controller.prepare(); controller.pause() }
        awaitUi("automatic discovery on opening SAF video") {
            runBlocking { prefs.fileSettings(doc.toString()).first() }.subtitles.let { it.discovered && it.sources.any { s -> s.title == "embedded.ru.srt" } }
        }
    } catch (e: Throwable) {
        failure = e
        runCatching { uiMark(uiRead { "state=${player?.playbackState} position=${player?.currentPosition} error=${player?.playerError} tracks=${player?.currentTracks?.subtitleChoices()} cues=${player?.currentCues?.cues?.map { it.text }}" }) }
        runCatching { uiScreenshot("subtitle-failure.png") }
    }
    finally {
        runOnMainSync { player?.pause(); activity?.finish(); future?.let { MediaController.releaseFuture(it) } }
        runBlocking { prefs.updateSettings { original }; prefs.setSubtitles(uri, saved.subtitles) }
    }
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
        putString("stream", if (failure == null) "PASS: embedded extraction/timestamps and decoding, Android ASS parse, language/track/off, dual rendering, delay, transcript seek, style/timing UI, SAF import/edit/export, folder grant and automatic sibling discovery\n" else "FAIL: ${android.util.Log.getStackTraceString(failure)}")
    })
}
