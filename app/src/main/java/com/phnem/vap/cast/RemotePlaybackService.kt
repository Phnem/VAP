package com.phnem.vap.cast

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.phnem.vap.R
import com.phnem.vap.player.PlayerActivity
import com.phnem.vap.player.PlaybackService
import kotlinx.coroutines.launch

/** Keeps proxy and renderer polling alive while casting; owned session is never silently restarted. */
class RemotePlaybackService : Service() {
    private var wake: android.os.PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = RemoteRuntime.get(this)
        if (intent?.action == STOP) {
            runtime.scope.launch { try { runtime.returnToPhone(false) } finally { stopSelf() } }
            return START_NOT_STICKY
        }
        if (!runtime.active) { stopSelf(); return START_NOT_STICKY }
        if ((runtime.manager.connection.value as? RemoteConnection.Connected)?.delivery != DeliveryMode.DIRECT && wake == null) {
            wake = getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "VAP:cast").apply {
                setReferenceCounted(false); acquire(6 * 3600_000L)
            }
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.cast_title), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 81, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 82, Intent(this, PlayerActivity::class.java).setAction(PlaybackService.ACTION_RESUME), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ph_television_simple_fill)
            .setContentTitle(getString(R.string.cast_on, intent?.getStringExtra("device").orEmpty()))
            .setContentText(getString(R.string.cast_notification)).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open).addAction(0, getString(R.string.cast_stop), stop).build()
        ServiceCompat.startForeground(this, 821, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }
    override fun onDestroy() { if (wake?.isHeld == true) wake?.release(); wake = null; super.onDestroy() }
    companion object {
        private const val CHANNEL = "remote_playback"
        private const val STOP = "com.phnem.vap.cast.STOP"
        fun start(context: Context, name: String) { context.startForegroundService(Intent(context, RemotePlaybackService::class.java).putExtra("device", name)) }
        fun stop(context: Context) { context.stopService(Intent(context, RemotePlaybackService::class.java)) }
    }
}
