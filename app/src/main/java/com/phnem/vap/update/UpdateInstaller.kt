package com.phnem.vap.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Передача скачанного APK системному установщику (окно «Установить?»). Окно открывается сразу, без
 * сессии `PackageInstaller` с ожиданием ответа: на части оболочек сессия молча зависала после `commit`,
 * а начатая в ней проверка пакета сбрасывалась и шла заново.
 */
class UpdateInstaller(private val context: Context) {

    /** Разрешил ли пользователь этому приложению устанавливать пакеты. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Бросить все незавершённые сессии этого приложения: зависшая сессия мешает системному установщику. */
    fun abandonOwnSessions() {
        val installer = context.packageManager.packageInstaller
        runCatching {
            installer.mySessions.forEach { info ->
                Log.i(TAG, "abandon session ${info.sessionId} (progress ${info.progress})")
                runCatching { installer.abandonSession(info.sessionId) }
            }
        }.onFailure { Log.w(TAG, "could not list own sessions", it) }
    }

    /** Намерение «установить этот APK» (файл отдаётся через FileProvider). */
    fun systemInstallIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Открыть окно системного установщика сразу. false — на устройстве нет обработчика. */
    fun openWithSystemInstaller(file: File): Boolean = runCatching {
        context.startActivity(systemInstallIntent(file))
        Log.i(TAG, "opened ${file.name} with the system installer")
        true
    }.getOrElse {
        Log.w(TAG, "could not open the system installer", it)
        false
    }

    private companion object {
        const val TAG = "AppUpdate"
    }
}
