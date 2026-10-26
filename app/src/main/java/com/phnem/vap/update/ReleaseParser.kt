package com.phnem.vap.update

import org.json.JSONArray

/** Последний релиз на GitHub с APK нужной сборки. */
data class GithubRelease(
    val tagName: String,
    val htmlUrl: String,
    val downloadUrl: String,
    val body: String?,
    val apkSize: Long,
    /** SHA-256 APK в нижнем регистре, если GitHub его сообщил (поле `digest`); иначе null. */
    val sha256: String?,
)

/**
 * Разбор ответа `GET /repos/{owner}/{repo}/releases`. Отдельно от сети, чтобы проверять на образцах.
 *
 * Берётся самый свежий опубликованный релиз (черновики API не отдаёт без токена). Среди вложений
 * выбирается APK именно этой сборки: в релизе лежат и `-foss.apk`, и `-cast.apk`, а ещё контрольные
 * суммы и исходники.
 */
object ReleaseParser {

    fun parse(response: String, flavor: String): GithubRelease? {
        val releases = runCatching { JSONArray(response) }.getOrNull() ?: return null
        val root = (0 until releases.length()).asSequence()
            .mapNotNull { releases.optJSONObject(it) }
            .firstOrNull { !it.optBoolean("draft", false) } ?: return null
        val tag = root.optString("tag_name").takeIf { it.isNotEmpty() } ?: return null
        val assets = root.optJSONArray("assets")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }.orEmpty()
        val apk = pickApk(assets.map { it.optString("name") to it }, flavor)?.second
        val url = apk?.optString("browser_download_url").orEmpty()
        return GithubRelease(
            tagName = tag,
            htmlUrl = root.optString("html_url"),
            downloadUrl = url,
            body = root.optString("body").takeIf { it.isNotBlank() },
            apkSize = apk?.optLong("size", 0L) ?: 0L,
            sha256 = apk?.optString("digest")?.let(::sha256Of),
        )
    }

    /**
     * Какой APK брать. Сначала тот, в имени которого есть название нашей сборки (`-foss`, `-cast`); если
     * такого нет — единственный «безымянный» APK, то есть не принадлежащий другой сборке.
     */
    fun <T> pickApk(named: List<Pair<String, T>>, flavor: String): Pair<String, T>? {
        val apks = named.filter { it.first.endsWith(".apk", ignoreCase = true) }
        val mine = apks.firstOrNull { it.first.contains("-$flavor", ignoreCase = true) }
        if (mine != null) return mine
        val others = FLAVORS - flavor
        return apks.firstOrNull { apk -> others.none { apk.first.contains("-$it", ignoreCase = true) } }
    }

    /** `digest` приходит как `sha256:<hex>`; другие алгоритмы и мусор не принимаются. */
    fun sha256Of(digest: String): String? {
        if (!digest.startsWith("sha256:")) return null
        val hex = digest.removePrefix("sha256:").lowercase()
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    private val FLAVORS = setOf("foss", "cast")
}

/**
 * Сравнивает теги GitHub и `versionName` по числам после «v»: релиз новее, если его major.minor.patch
 * больше. Суффикс (Beta, Stable…) ничего не решает, потому что каждый релиз поднимает номер. Единственное
 * исключение — **Alpha**: такой тег игнорируется и никогда не предлагается.
 */
object AppReleaseVersionComparer {

    fun isRemoteSemanticallyNewer(localRaw: String, remoteRaw: String): Boolean = runCatching {
        val local = ParsedVersion.parse(localRaw) ?: return@runCatching false
        val remote = ParsedVersion.parse(remoteRaw) ?: return@runCatching false
        if (remote.isAlpha) return@runCatching false
        compare(remote.core, local.core) > 0
    }.getOrElse { false }

    private fun compare(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        val major = a.first.compareTo(b.first)
        if (major != 0) return major
        val minor = a.second.compareTo(b.second)
        if (minor != 0) return minor
        return a.third.compareTo(b.third)
    }

    private data class ParsedVersion(val core: Triple<Int, Int, Int>, val isAlpha: Boolean) {
        companion object {
            fun parse(raw: String): ParsedVersion? {
                // Префикс снимается без учёта регистра: «V0.2» и «v0.2» — одна версия.
                val clean = raw.trim().removePrefix("v").removePrefix("V").trim().ifBlank { return null }
                val dashParts = clean.split('-', limit = 2)
                val numbers = dashParts[0].trim().split('.').map { segment -> segment.toIntOrNull() ?: 0 }
                val suffix = dashParts.getOrElse(1) { "" }.lowercase()
                return ParsedVersion(
                    core = Triple(numbers.getOrElse(0) { 0 }, numbers.getOrElse(1) { 0 }, numbers.getOrElse(2) { 0 }),
                    isAlpha = suffix.startsWith("alpha"),
                )
            }
        }
    }
}
