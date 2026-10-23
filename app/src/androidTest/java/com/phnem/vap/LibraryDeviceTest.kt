package com.phnem.vap

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.os.Bundle
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.phnem.vap.library.*
import com.phnem.vap.player.*
import com.phnem.vap.player.model.PlayableItem
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit

/** Uses only named synthetic fixtures; never clears application data or deletes user media. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Instrumentation.runLibraryDeviceTests() {
    var failure: Throwable? = null
    var activity: MainActivity? = null
    var model: LibraryViewModel? = null
    var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var controller: MediaController? = null
    var added: android.net.Uri? = null
    val store = TreeStore(targetContext); val saved = store.settings()
    try {
        check(VideoPermissions.access(targetContext) == VideoAccess.FULL) { "Grant full video access through first-launch dialog before this suite" }
        check(saved.folderSort == SortSpec(FolderSortKey.COUNT, true)) { "Count-descending default/migration missing" }
        targetContext.getSharedPreferences("vap_app", 0).edit().putBoolean("first_run_done", true).commit() // the first-run screen would cover the library under test
        val monitor = addMonitor(MainActivity::class.java.name, null, false)
        uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n com.phnem.vap/.MainActivity").use { java.io.FileInputStream(it.fileDescriptor).use { s -> s.readBytes() } }
        activity = waitForMonitorWithTimeout(monitor, 10000) as? MainActivity; removeMonitor(monitor); check(activity != null)
        runOnMainSync { model = ViewModelProvider(activity!!)[LibraryViewModel::class.java] }
        awaitUi("automatic device scan") { uiRead { model!!.state.value.let { !it.scanning && it.data.nodes.any { f -> f.files.any { it.name == "vap_library_test_many_1.mp4" } } } } }
        val nodes = uiRead { model!!.state.value.data.nodes }
        val many = nodes.single { f -> f.files.any { it.name == "vap_library_test_many_1.mp4" } }
        val one = nodes.single { f -> f.files.any { it.name == "vap_library_test_one.mp4" } }
        check(many.files.size == 3 && one.files.size == 1)
        val folders = uiRead { model!!.state.value.folders }
        check(folders.map { it.totalCount }.zipWithNext().all { (a, b) -> a >= b }) { "Folder count order is not descending" }
        check(folders.indexOfFirst { it.id == many.id } < folders.indexOfFirst { it.id == one.id })
        check(ScanCache(targetContext).load().orEmpty().none { DeviceVideoIndex.isDeviceFolder(it.id) }) { "MediaStore access leaked to persistent cache" }
        runOnMainSync { model!!.setQuery("vap_library_test") }
        labelNode("Many"); labelNode("One"); uiScreenshot("library-auto-counts.png")
        clickUi(labelNode("Many"))
        labelNode("vap library test many 1")
        uiScreenshot("library-auto-folder.png")
        uiMark("automatic folders, count order and folder navigation")

        val source = File(targetContext.filesDir, "video-fixtures/h264-24.mp4")
        check(source.isFile)
        added = targetContext.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "vap_library_test_observer.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VAP-Library-Auto-Test/Many/")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }) ?: error("Cannot create test media")
        targetContext.contentResolver.openOutputStream(added!!)?.use { output -> source.inputStream().use { it.copyTo(output) } } ?: error("Cannot write fixture")
        targetContext.contentResolver.update(added!!, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        awaitUi("MediaStore observer insert") { uiRead { model!!.state.value.data.byId[many.id]?.files?.size == 4 } }
        targetContext.contentResolver.delete(added!!, null, null); added = null
        awaitUi("MediaStore observer delete") { uiRead { model!!.state.value.data.byId[many.id]?.files?.size == 3 } }
        uiMark("live insert/delete without rescan button")

        runOnMainSync { future = PlaybackConnection.connect(targetContext) }
        controller = future!!.get(15, TimeUnit.SECONDS)
        val player = controller!!
        runOnMainSync { player.setMediaItem(PlayableItem(many.files.first().key, "Indexed library test").toMediaItem()); player.prepare(); player.play() }
        awaitUi("MediaStore content URI plays") { uiRead { player.isPlaying && player.playerError == null && player.playbackState == Player.STATE_READY } }
    } catch (error: Throwable) { failure = error; runCatching { uiScreenshot("library-auto-failure.png") } }
    finally {
        added?.let { runCatching { targetContext.contentResolver.delete(it, null, null) } }
        runOnMainSync { controller?.pause(); future?.let { MediaController.releaseFuture(it) }; model?.setQuery(""); activity?.finish() }
        store.save(saved)
    }
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
        putString("stream", if (failure == null) "PASS: first-library automatic MediaStore scan, count-descending default/order, real folder UI, transient media cache, live insert/delete, playable indexed content URI\n"
        else "FAIL: ${android.util.Log.getStackTraceString(failure)}\n")
    })
}
