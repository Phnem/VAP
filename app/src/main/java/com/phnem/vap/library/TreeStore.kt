package com.phnem.vap.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import com.phnem.vap.player.model.PlayableItem
import org.json.JSONArray
import org.json.JSONObject

/** Раздел главного экрана (меню «Библиотека ▾»). */
enum class LibraryScope { FOLDERS, HIDDEN, PLAYLISTS }

/** Настройки вида библиотеки; хранятся между запусками. */
data class LibrarySettings(
    val filter: LibraryFilter = LibraryFilter.ALL,
    val folderSort: SortSpec<FolderSortKey> = SortSpec(FolderSortKey.COUNT, descending = true),
    val fileSort: SortSpec<FileSortKey> = SortSpec(FileSortKey.NAME),
    val treeMode: Boolean = false,
    val grid: Boolean = true,
    val scope: LibraryScope = LibraryScope.FOLDERS,
    /** Выключено (по умолчанию): все папки матово-чёрные; включено: у каждой свой цвет из палитры. */
    val coloredFolders: Boolean = false,
)

/**
 * Выбранные папки (деревья SAF), настройки вида и последняя сессия просмотра.
 * Права на чтение закрепляются, чтобы пережить перезапуск.
 */
class TreeStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    init {
        // Apply the corrected library default once to existing installations too.
        if (!prefs.getBoolean("media_count_sort_v1", false)) prefs.edit {
            putString(KEY_FOLDER_SORT, FolderSortKey.COUNT.name)
            putBoolean(KEY_FOLDER_DESC, true)
            putBoolean("media_count_sort_v1", true)
        }
    }

    fun trees(): List<Uri> = prefs.getStringSet(KEY_TREES, emptySet()).orEmpty().map(Uri::parse)

    fun add(tree: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.edit { putStringSet(KEY_TREES, trees().map(Uri::toString).toSet() + tree.toString()) }
    }

    fun remove(tree: Uri) {
        prefs.edit { putStringSet(KEY_TREES, trees().map(Uri::toString).toSet() - tree.toString()) }
    }

    fun settings(): LibrarySettings = LibrarySettings(
        filter = enumOf(KEY_FILTER, LibraryFilter.ALL),
        folderSort = SortSpec(enumOf(KEY_FOLDER_SORT, FolderSortKey.COUNT), prefs.getBoolean(KEY_FOLDER_DESC, true)),
        fileSort = SortSpec(enumOf(KEY_FILE_SORT, FileSortKey.NAME), prefs.getBoolean(KEY_FILE_DESC, false)),
        treeMode = prefs.getBoolean(KEY_TREE_MODE, false),
        grid = prefs.getBoolean(KEY_GRID, true),
        coloredFolders = prefs.getBoolean(KEY_COLORED_FOLDERS, false),
        // Раздел не сохраняем: приложение всегда открывается на папках.
    )

    fun save(settings: LibrarySettings) = prefs.edit {
        putString(KEY_FILTER, settings.filter.name)
        putString(KEY_FOLDER_SORT, settings.folderSort.key.name)
        putBoolean(KEY_FOLDER_DESC, settings.folderSort.descending)
        putString(KEY_FILE_SORT, settings.fileSort.key.name)
        putBoolean(KEY_FILE_DESC, settings.fileSort.descending)
        putBoolean(KEY_TREE_MODE, settings.treeMode)
        putBoolean(KEY_GRID, settings.grid)
        putBoolean(KEY_COLORED_FOLDERS, settings.coloredFolders)
    }

    /** Очередь последнего запуска плеера: «Продолжить» восстанавливает именно её (4.2.7). */
    fun saveSession(items: List<PlayableItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("u", it.uri).put("t", it.title).put("m", it.mimeType.orEmpty()).put("s", it.server?.json().orEmpty()).put("p", it.startPositionMs)) }
        prefs.edit { putString(KEY_SESSION, arr.toString()) }
    }

    fun session(): List<PlayableItem> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_SESSION, null) ?: return emptyList())
        List(arr.length()) { val item = arr.getJSONObject(it); PlayableItem(item.getString("u"), item.optString("t"), item.optString("m").ifBlank { null }, com.phnem.vap.network.ServerPlaybackRef.parse(item.optString("s"), forceRefresh = true), item.optLong("p").coerceAtLeast(0)) }
    }.getOrDefault(emptyList())

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        runCatching { enumValueOf<E>(prefs.getString(key, null) ?: return default) }.getOrDefault(default)

    private companion object {
        const val KEY_TREES = "trees"
        const val KEY_GRID = "grid_view"
        const val KEY_FILTER = "filter"
        const val KEY_FOLDER_SORT = "folder_sort"
        const val KEY_FOLDER_DESC = "folder_sort_desc"
        const val KEY_FILE_SORT = "file_sort"
        const val KEY_FILE_DESC = "file_sort_desc"
        const val KEY_TREE_MODE = "tree_mode"
        const val KEY_COLORED_FOLDERS = "colored_folders"
        const val KEY_SESSION = "session"
    }
}
