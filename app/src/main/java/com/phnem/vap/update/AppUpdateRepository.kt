package com.phnem.vap.update

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.phnem.vap.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private val Context.updateStore by preferencesDataStore(name = "app_update")

private val KEY_KIND = stringPreferencesKey("persisted_kind")
private val KEY_LATEST_TAG = stringPreferencesKey("latest_tag")
private val KEY_DOWNLOAD_URL = stringPreferencesKey("download_url")
private val KEY_HTML_URL = stringPreferencesKey("html_url")
private val KEY_APK_SIZE = longPreferencesKey("apk_size_bytes")
private val KEY_SHA256 = stringPreferencesKey("latest_sha256")
private val KEY_CHANGELOG_MD = stringPreferencesKey("changelog_markdown")
private val KEY_LAST_CHECK_MS = longPreferencesKey("last_success_check_ms")
private val KEY_STARTUP_DISMISSED_TAG = stringPreferencesKey("startup_sheet_dismissed_tag")

/** Не чаще этого фоновая проверка ходит в GitHub: у анонимных запросов лимит 60 в час. */
private const val AUTO_CHECK_THROTTLE_MS = 4L * 60L * 60L * 1000L

/**
 * Что известно о последнем релизе: запрос к GitHub и запись ответа в собственный DataStore. По этой записи
 * состояние обновления восстанавливается после перезапуска, не дожидаясь сети.
 */
class AppUpdateRepository(context: Context, private val client: OkHttpClient) {

    private val store = context.applicationContext.updateStore

    val snapshot: Flow<UpdateSnapshot> = store.data.map { it.toSnapshot() }.distinctUntilChanged()

    /**
     * @param force true — спросить GitHub в любом случае (кнопка «Проверить сейчас»).
     * @param maxAgeMs пока прошлая успешная проверка не старше этого, GitHub не спрашиваем (при
     *  [force] = false). Запуск и возврат в приложение ставят короткий срок: релиз, вышедший после прошлой
     *  проверки, иначе не находился бы до четырёх часов.
     * @return false, если связаться с GitHub не удалось.
     */
    suspend fun refresh(
        force: Boolean,
        localVersion: String,
        maxAgeMs: Long = AUTO_CHECK_THROTTLE_MS,
    ): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val last = store.data.first()[KEY_LAST_CHECK_MS] ?: 0L
        if (!force && last > 0L && now - last < maxAgeMs) return@withContext true

        val release = try {
            fetchLatest()
        } catch (e: IOException) {
            store.edit { it[KEY_KIND] = PersistedKind.ERROR.name }
            return@withContext false
        }
        store.edit { ed ->
            if (release != null) {
                val newer = AppReleaseVersionComparer.isRemoteSemanticallyNewer(localVersion.ifBlank { "0.0.0" }, release.tagName)
                ed[KEY_KIND] = (if (newer) PersistedKind.UPDATE_AVAILABLE else PersistedKind.NO_UPDATE).name
                ed[KEY_LATEST_TAG] = release.tagName
                ed[KEY_DOWNLOAD_URL] = release.downloadUrl
                ed[KEY_HTML_URL] = release.htmlUrl
                ed[KEY_APK_SIZE] = release.apkSize
                release.sha256?.let { ed[KEY_SHA256] = it } ?: ed.remove(KEY_SHA256)
                release.body?.let { ed[KEY_CHANGELOG_MD] = it } ?: ed.remove(KEY_CHANGELOG_MD)
            } else {
                ed[KEY_KIND] = PersistedKind.NO_UPDATE.name
                listOf(KEY_LATEST_TAG, KEY_DOWNLOAD_URL, KEY_HTML_URL, KEY_SHA256, KEY_CHANGELOG_MD).forEach { ed.remove(it) }
                ed[KEY_APK_SIZE] = 0L
            }
            ed[KEY_LAST_CHECK_MS] = now
        }
        true
    }

    private fun fetchLatest(): GithubRelease? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "VAP/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("empty body")
            return ReleaseParser.parse(body, BuildConfig.FLAVOR)
        }
    }

    /** Окно закрыли: этот релиз при запуске больше не предлагаем. */
    suspend fun dismissStartupOverlayForCurrentRelease() {
        val tag = store.data.first().toSnapshot().latestTag ?: return
        store.edit { it[KEY_STARTUP_DISMISSED_TAG] = tag }
    }

    private fun Preferences.toSnapshot(): UpdateSnapshot {
        val kind = runCatching { this[KEY_KIND]?.let { PersistedKind.valueOf(it) } }.getOrNull() ?: PersistedKind.IDLE
        return UpdateSnapshot(
            persistedKind = kind,
            latestTag = this[KEY_LATEST_TAG],
            latestDownloadUrl = this[KEY_DOWNLOAD_URL],
            latestHtmlUrl = this[KEY_HTML_URL],
            latestApkSizeBytes = (this[KEY_APK_SIZE] ?: 0L).takeIf { it > 0L },
            latestSha256 = this[KEY_SHA256],
            changelogMarkdown = this[KEY_CHANGELOG_MD],
            lastSuccessfulCheckEpochMs = this[KEY_LAST_CHECK_MS] ?: 0L,
            startupDismissedTag = this[KEY_STARTUP_DISMISSED_TAG],
        )
    }
}
