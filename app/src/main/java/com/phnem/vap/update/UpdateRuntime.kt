package com.phnem.vap.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.util.concurrent.TimeUnit

/** Всё, что нужно окну и воркеру: менеджер обновления и проверка APK. Один на процесс. */
class UpdateKit(val manager: AppUpdateManager, val verifier: ApkVerifier)

object UpdateRuntime {

    @Volatile
    private var kit: UpdateKit? = null

    fun get(context: Context): UpdateKit = kit ?: synchronized(this) {
        kit ?: build(context.applicationContext).also { kit = it }
    }

    private fun build(context: Context): UpdateKit {
        // HTTP/1.1: параллельные куски одного файла иначе ушли бы в одно HTTP/2-соединение, и выигрыша нет.
        val client = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val verifier = ApkVerifier(context)
        val manager = AppUpdateManager(
            context = context,
            policy = UpdatePolicy(OfficialBuild(context)),
            repository = AppUpdateRepository(context, client),
            downloader = UpdateDownloader(client, File(context.filesDir, "updates")),
            verifier = verifier,
            installer = UpdateInstaller(context),
            notifier = UpdateNotifier(context),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        return UpdateKit(manager, verifier)
    }
}
