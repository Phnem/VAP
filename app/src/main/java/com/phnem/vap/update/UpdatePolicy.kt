package com.phnem.vap.update

import android.content.Context
import com.phnem.vap.BuildConfig

/**
 * Как приложение обновляется. Решает подпись APK, а не переключатель:
 *  - сборка, подписанная ключом автора (GitHub, Obtainium), обновляется сама: релизы проверяются,
 *    скачиваются и ставятся без лишних шагов;
 *  - всё остальное (F-Droid подписывает своим ключом, пересборки из исходников) не обновляется изнутри
 *    вовсе: там обновления приходят из магазина, и включить встроенные нельзя.
 */
enum class UpdateMode { Auto, Unavailable }

class UpdatePolicy(private val official: OfficialBuild) {

    val mode: UpdateMode by lazy { if (official.isOfficial) UpdateMode.Auto else UpdateMode.Unavailable }

    val isAuto: Boolean get() = mode == UpdateMode.Auto
}

/** Подписана ли работающая сборка ключом автора. */
class OfficialBuild(private val context: Context) {

    val isOfficial: Boolean by lazy {
        BuildConfig.DEBUG || matches(ApkVerifier(context).installedFingerprints(), OFFICIAL_SHA256)
    }

    companion object {
        /**
         * SHA-256 сертификата, которым подписаны релизы автора (CN=Evgeniy Idelchik). Публичное значение:
         * его же показывает `apksigner verify --print-certs` на любом релизном APK. Несколько записей —
         * на случай смены ключа: старый и новый должны жить рядом.
         */
        val OFFICIAL_SHA256: Set<String> = setOf(
            "1bc6a6321cd554475b31c2698514e89331922083bd1edc304fe2b13ab19e91e5",
        )

        /** Сборка официальная, если подпись есть и ВСЕ подписанты — наши. */
        fun matches(fingerprints: Collection<String>, official: Set<String>): Boolean =
            fingerprints.isNotEmpty() && fingerprints.all { it.lowercase() in official }
    }
}
