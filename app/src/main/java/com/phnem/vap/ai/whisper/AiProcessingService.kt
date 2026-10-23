package com.phnem.vap.ai.whisper

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.phnem.vap.R
import com.phnem.vap.player.PlayerActivity
import com.phnem.vap.player.PlaybackService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** User-started work only; no boot/process restart. Completed chunks survive cancellation/timeouts. */
class AiProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = AiRuntime.get(this)
        if (intent?.action == CANCEL) { runtime.cancel(); if (!runtime.state.value.running) stopSelf(); return START_NOT_STICKY }
        val downloads = intent?.getBooleanExtra("download", false) == true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.ai_whisper), NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getService(this, 910, Intent(this, javaClass).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 911, Intent(this, PlayerActivity::class.java).setAction(PlaybackService.ACTION_RESUME), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun notification(percent: Int) = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(if (downloads) R.string.ai_downloading else runtime.state.value.title))
            .setContentText(getString(R.string.ai_background)).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, percent, downloads).setContentIntent(open).addAction(0, getString(R.string.ai_cancel), cancel).build()
        val type = if (!downloads && Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try {
            // Core 1.15 masks out the newer mediaProcessing bit even on Android 15+.
            if (Build.VERSION.SDK_INT >= 35) startForeground(910, notification(0), type)
            else ServiceCompat.startForeground(this, 910, notification(0), type)
        } catch (failure: Exception) {
            android.util.Log.w("VapWhisper", "Cannot enter foreground (${failure.javaClass.simpleName})")
            runtime.backgroundFailed(); stopSelf(); return START_NOT_STICKY
        }
        if (wake == null) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VAP:whisper").apply { setReferenceCounted(false); acquire(6 * 3600_000L) }
        observer?.cancel()
        observer = scope.launch {
            runtime.state.collectLatest { state ->
                if (!state.running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId) }
                else manager.notify(910, notification(state.percent))
            }
        }
        // Also bound processing on older Android, which has no mediaProcessing timeout callback.
        scope.launch { delay(5 * 3600_000L + 45 * 60_000L); runtime.cancel(); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { AiRuntime.get(this).cancel(); stopSelf() }
    override fun onDestroy() { scope.cancel(); if (wake?.isHeld == true) wake?.release(); wake = null; super.onDestroy() }
    companion object { private const val CHANNEL = "whisper_processing"; const val CANCEL = "com.phnem.vap.ai.CANCEL" }
}
