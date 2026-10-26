package com.phnem.vap.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/** Итог проверки скачанного APK перед установкой. */
sealed interface ApkVerdict {
    data object Ok : ApkVerdict
    data class Rejected(val reason: String) : ApkVerdict
}

/**
 * Проверяет скачанный APK до того, как отдать его установщику: это наш пакет, версия не ниже
 * установленной и подписан тем же ключом, что и работающее приложение. Установщик Android всё равно
 * откажет на чужой подписи, но тогда пользователь увидит системную ошибку после скачивания 40 МБ, а
 * не нашу причину; к тому же чужой файл не должен ни доходить до установщика, ни лежать на диске.
 */
class ApkVerifier(private val context: Context) {

    fun verify(file: File): ApkVerdict {
        val pm = context.packageManager
        val archive = runCatching { archiveInfo(pm, file) }.getOrNull()
            ?: return ApkVerdict.Rejected("not a valid APK")
        if (archive.packageName != context.packageName) {
            return ApkVerdict.Rejected("package ${archive.packageName}")
        }
        val installed = pm.getPackageInfo(context.packageName, 0)
        if (PackageInfoCompat.getLongVersionCode(archive) < PackageInfoCompat.getLongVersionCode(installed)) {
            return ApkVerdict.Rejected("older version")
        }
        val offered = signers(archive)
        val current = signers(installedWithSigners(pm))
        return if (sameSigners(offered, current)) ApkVerdict.Ok else ApkVerdict.Rejected("different signing key")
    }

    /** SHA-256 сертификатов, которыми подписано работающее приложение. */
    fun installedFingerprints(): List<String> = signers(installedWithSigners(context.packageManager))

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun installedWithSigners(pm: PackageManager): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): List<String> {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return raw.orEmpty().map { sha256Hex(it.toByteArray()) }.sorted()
    }

    companion object {
        /** Подписи совпадают, если они есть и наборы одинаковы. Вынесено для проверки без Android. */
        fun sameSigners(offered: List<String>, current: List<String>): Boolean =
            offered.isNotEmpty() && offered.sorted() == current.sorted()

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
