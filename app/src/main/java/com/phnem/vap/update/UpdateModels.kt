package com.phnem.vap.update

import java.io.File

/** Релиз, до которого можно обновиться: что скачать и чем проверить скачанное. */
data class UpdateRelease(
    val tag: String,
    val htmlUrl: String,
    val downloadUrl: String,
    /** Размер APK по данным релиза; 0 — неизвестен. */
    val sizeBytes: Long,
    /** SHA-256 APK, если GitHub его сообщил. */
    val sha256: String?,
    val changelogMarkdown: String?,
)

/** Почему обновление не удалось. Каждой причине — своя строка в окне. */
enum class UpdateFailure {
    /** Не удалось узнать о релизе (сеть, лимит GitHub). */
    CHECK,

    /** Скачивание оборвалось. */
    DOWNLOAD,

    /** Файл не прошёл проверку: другой размер, контрольная сумма или чужая подпись. */
    VERIFY,

    /** Система отказалась ставить (нет места, несовместимый пакет, пользователь отменил). */
    INSTALL,
}

/**
 * Единое состояние обновления приложения. Живёт в [AppUpdateManager], а не в экране: загрузка не
 * зависит от того, открыто ли окно, и переживает поворот и закрытие окна.
 */
sealed interface UpdateState {

    /** Встроенные обновления выключены подписью (F-Droid и пересборки). */
    data object Unavailable : UpdateState

    /** Ещё не проверяли. */
    data object Idle : UpdateState

    data object Checking : UpdateState

    data class UpToDate(val checkedAtMillis: Long) : UpdateState

    /** Есть новый релиз, файл не скачан. */
    data class Available(val release: UpdateRelease) : UpdateState

    data class Downloading(
        val release: UpdateRelease,
        val doneBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
    ) : UpdateState {
        val fraction: Float
            get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    /** Файл скачан и проверен, ждёт установки. */
    data class Ready(val release: UpdateRelease, val file: File) : UpdateState

    /** Ставить нечем: пользователь не разрешил этому приложению устанавливать пакеты. */
    data class NeedsPermission(val release: UpdateRelease, val file: File) : UpdateState

    /** Система приняла пакет и ставит его; приложение вот-вот перезапустится. */
    data class Installing(val release: UpdateRelease) : UpdateState

    data class Failed(val release: UpdateRelease?, val reason: UpdateFailure, val detail: String? = null) : UpdateState
}

/** Что запомнила последняя проверка релиза. */
enum class PersistedKind { IDLE, UPDATE_AVAILABLE, NO_UPDATE, ERROR }

/** Снимок последней проверки: из него состояние восстанавливается после перезапуска приложения. */
data class UpdateSnapshot(
    val persistedKind: PersistedKind = PersistedKind.IDLE,
    val latestTag: String? = null,
    val latestDownloadUrl: String? = null,
    val latestHtmlUrl: String? = null,
    val latestApkSizeBytes: Long? = null,
    val latestSha256: String? = null,
    val changelogMarkdown: String? = null,
    val lastSuccessfulCheckEpochMs: Long = 0L,
    /** Релиз, окно которого пользователь уже закрыл: при запуске его больше не предлагаем. */
    val startupDismissedTag: String? = null,
)
