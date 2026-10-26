package com.phnem.vap.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.phnem.vap.MainActivity
import com.phnem.vap.R
import com.phnem.vap.ui.onboarding.AppLocale
import com.phnem.vap.ui.onboarding.AppPrefs

/**
 * Уведомления об обновлении. Нужны только когда само оно не прошло: система потребовала подтверждения
 * установки или не разрешено ставить пакеты. Пока приложение открыто, об этом говорит окно обновления, и
 * дублировать его уведомлением не нужно. Тексты — на языке приложения, а не системы.
 */
class UpdateNotifier(context: Context) {

    private val context = context.applicationContext
    private val manager = this.context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun localized(): Context = AppLocale.wrap(context, AppPrefs(context).language)

    private fun ensureChannel(text: Context) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, text.getString(R.string.upd_notification_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = text.getString(R.string.upd_notification_channel_desc) },
        )
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Обновление скачано, система ждёт подтверждения: тап открывает системное окно установки. */
    fun showReady(version: String, confirm: Intent?) {
        if (!canPost()) return
        val text = localized()
        ensureChannel(text)
        val target = confirm?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } ?: openAppIntent()
        val tap = PendingIntent.getActivity(context, REQUEST_READY, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(text.getString(R.string.upd_notification_ready_title))
                .setContentText(text.getString(R.string.upd_notification_ready_text, version))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    /** Установка не разрешена: тап ведёт на страницу разрешения для этого приложения. */
    fun showNeedsPermission(version: String) {
        if (!canPost()) return
        val text = localized()
        ensureChannel(text)
        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(context, REQUEST_PERMISSION, settings, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(text.getString(R.string.upd_notification_ready_title))
                .setContentText("$version · ${text.getString(R.string.upd_notification_permission_text)}")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    private fun openAppIntent(): Intent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private companion object {
        const val CHANNEL_ID = "app_update_channel"
        const val NOTIFICATION_ID = 0x0A9D
        const val REQUEST_READY = 0x0A9E
        const val REQUEST_PERMISSION = 0x0A9F
    }
}
