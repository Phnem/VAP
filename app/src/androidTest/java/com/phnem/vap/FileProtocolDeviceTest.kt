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
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

fun Instrumentation.runFileProtocolDeviceTests(keepFixture: Boolean = false) {
    val store = NetworkConnectionStore(targetContext)
    val ids = mutableListOf<String>()
    var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var player: MediaController? = null; var failure: Throwable? = null
    var activity: PlayerActivity? = null
    try {
        runBlocking { store.list().filter { it.name in setOf("FTP fixture", "SFTP fixture") && it.username == "fixture" && it.password == "fixture-secret" &&
            it.url in setOf("ftp://127.0.0.1:14421/", "sftp://127.0.0.1:14422/media/") }.forEach { store.remove(it.id) } }
        com.hierynomus.smbj.SMBClient(com.hierynomus.smbj.SmbConfig.builder().withSigningRequired(true)
            .withAuthenticators(listOf(com.hierynomus.smbj.auth.NtlmAuthenticator.Factory())).build()).close()
        val fingerprint = JSONObject(File(targetContext.filesDir, "network-server.json").readText()).getString("fingerprint")
        val ftp = NetworkConnection(name = "FTP fixture", kind = NetworkKind.FTP, url = "ftp://127.0.0.1:14421/", username = "fixture", password = "fixture-secret")
        val sftp = NetworkConnection(name = "SFTP fixture", kind = NetworkKind.SFTP, url = "sftp://127.0.0.1:14422/media/", username = "fixture", password = "fixture-secret", hostKey = fingerprint)
        runOnMainSync { future = PlaybackConnection.connect(targetContext) }; player = future!!.get(15, TimeUnit.SECONDS); val controller = player!!
        val sample = File(targetContext.filesDir, "vap-test.mp4").readBytes()
        for (connection in listOf(ftp, sftp)) {
            runBlocking { store.save(connection); ids += connection.id }
            val entries = runBlocking { RemoteFiles.list(connection, connection.rootUrl) }
            check(entries.any { it.name == "video.mp4" && !it.folder } && entries.any { it.name == "hls" && it.folder })
            val file = entries.first { it.name == "video.mp4" }
            RemoteFiles.open(connection, file.url).use { reader ->
                val buffer = ByteArray(4096)
                val first = reader.read(0, buffer, 0, buffer.size)
                check(first > 0 && buffer.copyOf(first).contentEquals(sample.copyOfRange(0, first)))
                val next = reader.read(65536, buffer, 0, buffer.size)
                check(next > 0 && buffer.copyOf(next).contentEquals(sample.copyOfRange(65536, 65536 + next)))
            }
            runOnMainSync { controller.setMediaItem(PlayableItem(file.url, connection.name).toMediaItem()); controller.prepare(); controller.play() }
            if (activity == null) {
                val monitor = addMonitor(PlayerActivity::class.java.name, null, false)
                uiAutomation.executeShellCommand("am start -W -f 0x10008000 -n com.phnem.vap/.player.PlayerActivity -a ${PlaybackService.ACTION_RESUME}").close()
                activity = waitForMonitorWithTimeout(monitor, 10000) as? PlayerActivity
                removeMonitor(monitor); check(activity != null)
            }
            val end = SystemClock.elapsedRealtime() + 30000
            while (SystemClock.elapsedRealtime() < end && !uiRead { controller.playerError != null || (controller.playbackState == Player.STATE_READY && controller.currentPosition > 500) }) SystemClock.sleep(100)
            check(uiRead { controller.playerError == null && controller.playbackState == Player.STATE_READY && controller.videoSize.width > 0 }) { uiRead { "${connection.kind} playback error=${controller.playerError}" } }
            runOnMainSync { controller.pause(); controller.seekTo(15_000) }
            awaitUi("${connection.kind} remote seek") { uiRead { controller.playbackState == Player.STATE_READY && controller.currentPosition in 14_900..15_100 } }
            uiMark("${connection.kind}: actual listing, offset reads, service decode and seek passed")
        }
        val wrongPin = NetworkConnection(name = "Wrong pin", kind = NetworkKind.SFTP, url = sftp.url, username = sftp.username, password = sftp.password,
            hostKey = "SHA256:" + "A".repeat(43))
        check(runCatching { runBlocking { RemoteFiles.list(wrongPin, wrongPin.rootUrl) } }.isFailure) { "Wrong SSH host key accepted" }
    } catch (e: Throwable) { failure = e }
    finally {
        runOnMainSync { player?.pause(); player?.clearMediaItems(); activity?.finish(); future?.let { MediaController.releaseFuture(it) } }
        if (!keepFixture || failure != null) runBlocking { ids.forEach { store.remove(it) } }
    }
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
        putString("stream", if (failure == null) "PASS: real FTP/SFTP listing, offset reads, video decode/seek, SSH pin rejection; SMB server not available\n" else "FAIL: ${android.util.Log.getStackTraceString(failure)}")
    })
}
