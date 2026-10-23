package com.phnem.vap.library

import java.util.Locale

/** Сезон, серия и название сериала, разобранные из имени файла. [season] = null, если в имени сезона нет. */
data class EpisodeInfo(val season: Int?, val episode: Int, val show: String)

/**
 * Распознавание серий по имени файла (4.2.8): `Show.S02E05.mkv`, `Show 2x05`, `Show - Episode 5`,
 * `[Group] Show - 05 [1080p].mkv`.
 *
 * Правила идут от самых надёжных к самым слабым; первое совпадение выигрывает. Разрешение
 * (`1920x1080`, `1080p`) и кодеки (`x264`) намеренно не считаются номером серии.
 */
object EpisodeParser {
    // Фигурные скобки квантификаторов — только открывающие: литеральная «}» роняет ICU на Android.
    private val seasonEpisode = Regex("""(?i)(?<![a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,3})(?!\d)""")
    private val crossForm = Regex("""(?i)(?<![0-9a-z])(\d{1,2})x(\d{2,3})(?![0-9a-z])""")
    private val episodeWord = Regex("""(?i)(?<![a-z])(?:episode|ep|e)[ ._-]?(\d{1,3})(?![0-9a-z])""")
    private val dashNumber = Regex("""\s-\s(\d{1,3})(?:v\d)?(?=[\s.\[(_]|$)""")
    private val leadingGroup = Regex("""^\s*(\[[^\]]*\]\s*)+""")
    private val whitespace = Regex("""\s+""")

    /**
     * Разбор имени вызывается из компараторов сортировки и из группировки по сезонам, то есть много раз
     * на один и тот же файл, а четыре регулярки на каждое сравнение заметно тормозили открытие больших
     * папок. Результат по имени неизменен, поэтому запоминается (старые записи вытесняются).
     */
    private const val MEMO_LIMIT = 4096
    private val memo = object : LinkedHashMap<String, EpisodeInfo?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EpisodeInfo?>) = size > MEMO_LIMIT
    }

    fun parse(fileName: String): EpisodeInfo? = synchronized(memo) {
        if (memo.containsKey(fileName)) memo[fileName] else parseUncached(fileName).also { memo[fileName] = it }
    }

    private fun parseUncached(fileName: String): EpisodeInfo? {
        val name = fileName.substringBeforeLast('.', fileName)

        seasonEpisode.find(name)?.let { return info(name, it, season = it.groupValues[1].toInt(), episode = it.groupValues[2].toInt()) }
        crossForm.find(name)?.let { return info(name, it, season = it.groupValues[1].toInt(), episode = it.groupValues[2].toInt()) }
        episodeWord.find(name)?.let { return info(name, it, season = null, episode = it.groupValues[1].toInt()) }
        dashNumber.find(name)?.let { return info(name, it, season = null, episode = it.groupValues[1].toInt()) }
        return null
    }

    private fun info(name: String, match: MatchResult, season: Int?, episode: Int): EpisodeInfo {
        val head = name.substring(0, match.range.first)
        return EpisodeInfo(season = season, episode = episode, show = cleanTitle(head))
    }

    internal fun cleanTitle(raw: String): String =
        raw.replace(leadingGroup, "")
            .replace('.', ' ')
            .replace('_', ' ')
            .trim(' ', '-', '.', '_', '(', '[')
            .replace(whitespace, " ")
            .trim()

    /**
     * Заголовок секции в списке файлов: «Сезон 2» или «Эпизоды». Отдельно от разбора, чтобы текст
     * можно было локализовать в UI.
     */
    fun sectionKey(info: EpisodeInfo?): Int = info?.season ?: 0

    fun normalizeShow(show: String): String = show.lowercase(Locale.ROOT)
}
