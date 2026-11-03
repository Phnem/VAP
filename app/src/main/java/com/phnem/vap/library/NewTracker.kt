package com.phnem.vap.library

import android.content.Context
import androidx.core.content.edit
import com.phnem.vap.data.UserRepository

/** Видео и папки, которых пользователь ещё не видел. Меняется только в одну сторону: «новое» → «видел». */
data class NewState(val videos: Set<String>, val folders: Set<String>) {
    fun withoutVideos(uris: Collection<String>): NewState =
        if (uris.none { it in videos }) this else copy(videos = videos - uris.toSet())

    fun withoutFolder(id: String): NewState = if (id in folders) copy(folders = folders - id) else this

    companion object {
        val EMPTY = NewState(emptySet(), emptySet())
    }
}

/** Что отметить «видел» при первом сканировании источников и до какого уровня доступа дошла база устройства. */
class Baseline(val sources: Set<String>, val deviceLevel: Int, val videos: List<String>, val folders: List<String>)

/**
 * Чистые правила «нового»: без Android и без хранилища, поэтому проверяются JVM-тестами.
 *
 * Новое — видео и папка, которых нет в списке «видел». В этот список всё, что лежало в источнике при
 * его первом полном сканировании, попадает разом, поэтому библиотека, существовавшая до приложения,
 * новой не считается. Источник — хранилище устройства (MediaStore) или одно выбранное дерево SAF.
 */
object NewRules {
    const val DEVICE = "device"

    fun sourceOf(folderId: String): String =
        if (DeviceVideoIndex.isDeviceFolder(folderId)) DEVICE else folderId.substringBefore("/document/")

    /**
     * Источники, которые просканированы впервые. Хранилище устройства сканируется «заново», когда доступ
     * расширился (частичный → полный): новые разрешения не делают старые видео новыми. Если запрос к
     * MediaStore упал ([deviceScanned] = false), по устройству ничего не отмечается: список неполный.
     */
    fun baseline(
        nodes: List<VideoFolder>,
        sources: Set<String>,
        deviceLevel: Int,
        access: VideoAccess,
        deviceScanned: Boolean,
    ): Baseline {
        val taken = HashSet(sources)
        var level = deviceLevel
        val videos = ArrayList<String>()
        val folders = ArrayList<String>()
        val bySource = nodes.groupBy { sourceOf(it.id) }
        fun take(group: List<VideoFolder>) {
            for (node in group) {
                folders += node.id
                node.files.mapTo(videos) { it.key }
            }
        }
        for ((source, group) in bySource) {
            if (source == DEVICE || source in taken) continue
            taken += source
            take(group)
        }
        if (deviceScanned && access.ordinal > level) {
            level = access.ordinal
            take(bySource[DEVICE].orEmpty())
        }
        return Baseline(taken, level, videos, folders)
    }

    /** Новое среди [nodes]: только из источников, у которых уже есть база. */
    fun unseen(
        nodes: List<VideoFolder>,
        sources: Set<String>,
        deviceLevel: Int,
        seenVideos: Set<String>,
        seenFolders: Set<String>,
    ): NewState {
        val videos = HashSet<String>()
        val folders = HashSet<String>()
        for (node in nodes) {
            val source = sourceOf(node.id)
            val tracked = if (source == DEVICE) deviceLevel > 0 else source in sources
            if (!tracked) continue
            if (node.id !in seenFolders) folders += node.id
            for (file in node.files) if (file.key !in seenVideos) videos += file.key
        }
        return NewState(videos, folders)
    }
}

/**
 * Хранилище «нового»: список «видел» держится в памяти и пишется в БД сразу. Все методы блокирующие и
 * вызываются вне главного потока.
 */
class NewTracker(context: Context, private val user: UserRepository) {
    private val prefs = context.getSharedPreferences("library_new", Context.MODE_PRIVATE)
    private val lock = Any()
    private var seenVideos: MutableSet<String>? = null
    private var seenFolders: MutableSet<String>? = null

    private fun load(): Pair<MutableSet<String>, MutableSet<String>> {
        val videos = seenVideos ?: user.seenVideos().toHashSet().also { seenVideos = it }
        val folders = seenFolders ?: user.seenFolders().toHashSet().also { seenFolders = it }
        return videos to folders
    }

    /** Новое среди только что просканированных [nodes]; первое сканирование источника заодно становится базой. */
    fun refresh(nodes: List<VideoFolder>, access: VideoAccess, deviceScanned: Boolean): NewState = synchronized(lock) {
        val (videos, folders) = load()
        val sources = prefs.getStringSet(KEY_SOURCES, emptySet()).orEmpty().toSet()
        val level = prefs.getInt(KEY_DEVICE_LEVEL, 0)
        val base = NewRules.baseline(nodes, sources, level, access, deviceScanned)
        if (base.videos.isNotEmpty() || base.folders.isNotEmpty() || base.sources != sources || base.deviceLevel != level) {
            // Сначала записи, потом отметка источника: оборванная запись повторится, а не потеряет базу.
            user.markSeen(base.videos, base.folders)
            videos += base.videos
            folders += base.folders
            prefs.edit {
                putStringSet(KEY_SOURCES, base.sources)
                putInt(KEY_DEVICE_LEVEL, base.deviceLevel)
            }
        }
        NewRules.unseen(nodes, base.sources, base.deviceLevel, videos, folders)
    }

    fun markVideos(uris: Collection<String>) = synchronized(lock) {
        val (videos, _) = load()
        val added = uris.filter { videos.add(it) }
        if (added.isNotEmpty()) user.markSeen(added, emptyList())
    }

    fun markFolder(id: String) = synchronized(lock) {
        val (_, folders) = load()
        if (folders.add(id)) user.markSeen(emptyList(), listOf(id))
    }

    private companion object {
        const val KEY_SOURCES = "baselined_sources"
        const val KEY_DEVICE_LEVEL = "device_level"
    }
}
