package com.phnem.vap.data

import android.content.Context
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.phnem.vap.library.FileMeta
import com.phnem.vap.library.WatchInfo
import com.phnem.vap.library.WatchRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Единственная БД приложения (`vap.db`) и фабрики репозиториев. */
object VapData {
    private var database: VapDatabase? = null

    @Synchronized
    fun database(context: Context): VapDatabase = database ?: VapDatabase(
        AndroidSqliteDriver(VapDatabase.Schema, context.applicationContext, "vap.db"),
    ).also { database = it }

    fun history(context: Context) = HistoryRepository(database(context))
    fun user(context: Context) = UserRepository(database(context))
}

data class PlaylistSummary(val id: Long, val name: String, val itemCount: Int)

/** Запись очереди или плейлиста: [position] нужна, чтобы удалять конкретную строку. */
data class QueueEntry(val position: Long, val uri: String, val title: String)

data class Favorites(val folders: Set<String>, val files: Set<String>)

class HistoryRepository(db: VapDatabase) {
    private val q = db.vapQueries

    fun observe(): Flow<Map<String, WatchInfo>> =
        q.allHistory().asFlow().mapToList(Dispatchers.IO).map { rows ->
            rows.associate { it.uri to WatchInfo(it.position_ms, it.duration_ms, it.watched != 0L, it.last_played_at) }
        }

    fun get(uri: String): WatchInfo? = q.historyFor(uri).executeAsOneOrNull()
        ?.let { WatchInfo(it.position_ms, it.duration_ms, it.watched != 0L, it.last_played_at) }

    /** Записывает позицию; «просмотрено» вычисляется единым правилом [WatchRules]. */
    fun record(uri: String, title: String, positionMs: Long, durationMs: Long, nowMs: Long = System.currentTimeMillis()) {
        if (durationMs <= 0L || positionMs < 0L) return
        val watched = WatchRules.isWatched(positionMs, durationMs)
        q.upsertHistory(uri, title, positionMs, durationMs, nowMs, if (watched) 1L else 0L)
    }

    fun markWatched(uri: String, title: String, durationMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val d = durationMs.coerceAtLeast(1L)
        q.upsertHistory(uri, title, d, d, nowMs, 1L)
    }

    fun reset(uri: String) = q.deleteHistory(uri)

    /** Откуда продолжать: не с первых секунд, не после «просмотрено». */
    fun resumePosition(uri: String): Long {
        val w = get(uri) ?: return 0L
        return if (!w.watched && w.positionMs >= WatchRules.MIN_RESUME_MS) w.positionMs else 0L
    }
}

class UserRepository(private val db: VapDatabase) {
    private val q = db.vapQueries

    fun favorites(): Flow<Favorites> = q.allFavorites().asFlow().mapToList(Dispatchers.IO).map { rows ->
        Favorites(
            folders = rows.filter { it.kind == KIND_FOLDER }.map { it.key }.toSet(),
            files = rows.filter { it.kind == KIND_FILE }.map { it.key }.toSet(),
        )
    }

    fun setFavorite(isFolder: Boolean, key: String, on: Boolean) {
        val kind = if (isFolder) KIND_FOLDER else KIND_FILE
        if (on) q.addFavorite(kind, key) else q.removeFavorite(kind, key)
    }

    fun hidden(): Flow<Set<String>> = q.allHidden().asFlow().mapToList(Dispatchers.IO).map { it.toSet() }

    fun setHidden(folderId: String, hidden: Boolean) {
        if (hidden) q.hideFolder(folderId) else q.unhideFolder(folderId)
    }

    fun meta(): Flow<Map<String, FileMeta>> = q.allMeta().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.associate { it.uri to FileMeta(it.duration_ms, it.width.toInt(), it.height.toInt()) }
    }

    fun saveMeta(uri: String, meta: FileMeta) = q.upsertMeta(uri, meta.durationMs, meta.width.toLong(), meta.height.toLong())

    // ——— Плейлисты ———

    fun playlists(): Flow<List<PlaylistSummary>> = q.allPlaylists().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.map { PlaylistSummary(it.id, it.name, it.item_count.toInt()) }
    }

    fun createPlaylist(name: String, nowMs: Long = System.currentTimeMillis()): Long = db.transactionWithResult {
        q.insertPlaylist(name, nowMs)
        q.lastInsertedId().executeAsOne()
    }

    fun renamePlaylist(id: Long, name: String) = q.renamePlaylist(name, id)

    fun deletePlaylist(id: Long) = db.transaction {
        q.deletePlaylistItems(id)
        q.deletePlaylist(id)
    }

    fun playlistItems(id: Long): Flow<List<QueueEntry>> =
        q.playlistItems(id).asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { QueueEntry(it.position, it.uri, it.title) } }

    fun addToPlaylist(id: Long, entries: List<Pair<String, String>>) = db.transaction {
        var pos = q.nextPlaylistPosition(id).executeAsOne()
        entries.forEach { (uri, title) -> q.addPlaylistItem(id, pos++, uri, title) }
    }

    fun removeFromPlaylist(id: Long, position: Long) = q.removePlaylistItem(id, position)

    // ——— Очередь ———

    fun queue(): Flow<List<QueueEntry>> =
        q.queueItems().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { QueueEntry(it.position, it.uri, it.title) } }

    fun addToQueue(entries: List<Pair<String, String>>) = db.transaction {
        var pos = q.nextQueuePosition().executeAsOne()
        entries.forEach { (uri, title) -> q.addQueueItem(pos++, uri, title) }
    }

    fun removeFromQueue(position: Long) = q.removeQueueItem(position)

    fun clearQueue() = q.clearQueue()

    private companion object {
        const val KIND_FOLDER = "folder"
        const val KIND_FILE = "file"
    }
}
