package com.phnem.vap.player.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phnem.vap.player.ui.PlayerSettings
import com.phnem.vap.player.ui.VideoFit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CurrentFileSettings(val uri: String, val value: FilePlaybackSettings)

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val preferences = PlayerPreferences(app)
    private val currentUri = MutableStateFlow<String?>(null)
    private var nativeSubtitleJob: kotlinx.coroutines.Job? = null
    private var requestedNativeSubtitle: Pair<String, String>? = null
    val settings = preferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerSettings())
    val file = currentUri.filterNotNull().flatMapLatest { uri ->
        preferences.fileSettings(uri).map { CurrentFileSettings(uri, it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val saveFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun selectFile(uri: String) {
        if (currentUri.value != uri) nativeSubtitleJob?.cancel()
        currentUri.value = uri.takeIf { it.isNotBlank() }
    }
    fun prepareStyledEmbedded(uri: String, trackId: String) {
        if (currentUri.value != uri || trackId.isBlank()) return
        requestedNativeSubtitle = uri to trackId
        if (nativeSubtitleJob?.isActive == true) return
        nativeSubtitleJob = viewModelScope.launch {
            try {
                val initial = preferences.fileSettings(uri).first().subtitles
                if (!initial.assNative || (initial.selection != "auto" && !initial.selection.startsWith("embedded:"))) return@launch
                val storage = com.phnem.vap.player.subtitles.SubtitleStorage(getApplication())
                val stamp = com.phnem.vap.player.subtitles.SubtitleOrigin.stamp(getApplication(), uri)
                val cached = initial.sources.firstOrNull { it.embeddedTrackId == trackId && it.format == "ass" && it.originStamp == stamp }?.takeIf {
                    try { storage.readAss(it.id).isNotEmpty() } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                }
                val source = if (cached != null) cached else {
                    val video = android.net.Uri.parse(uri)
                    val extracted = com.phnem.vap.player.subtitles.EmbeddedSubtitleExtractor.extract(getApplication(), video)
                        .firstOrNull { it.trackId == trackId && it.assDocument != null } ?: return@launch
                    val fonts = try { com.phnem.vap.player.subtitles.MatroskaFonts.read(getApplication(), video) }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                    if (stamp != com.phnem.vap.player.subtitles.SubtitleOrigin.stamp(getApplication(), uri)) return@launch
                    storage.create(extracted.title, extracted.language, extracted.lines, extracted.assDocument, fonts, trackId, stamp)
                }
                val current = preferences.fileSettings(uri).first().subtitles
                if (currentUri.value == uri && requestedNativeSubtitle == uri to trackId && current.assNative && current.selection == initial.selection) {
                    val sources = (current.sources + source).distinctBy { it.id }
                    if (sources.size <= 32) preferences.setSubtitles(uri, current.copy(sources = sources, selection = source.id))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("VapSubtitles", "Native ASS preparation failed; retaining Media3 text", e) }
            finally {
                if (nativeSubtitleJob === kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]) {
                    nativeSubtitleJob = null
                    requestedNativeSubtitle?.takeIf { it.first == uri && it.first == currentUri.value && it.second != trackId }?.let {
                        prepareStyledEmbedded(it.first, it.second)
                    }
                }
            }
        }
    }
    fun updateSettings(update: (PlayerSettings) -> PlayerSettings) = write { preferences.updateSettings(update) }
    fun setSpeed(uri: String, speed: Float) = write { preferences.setSpeed(uri, speed) }
    fun setFit(uri: String, fit: VideoFit) = write { preferences.setFit(uri, fit) }
    fun setPicture(uri: String, value: com.phnem.vap.player.video.PictureSettings) = write { preferences.setPicture(uri, value) }
    fun setAudio(uri: String, label: String) = write { preferences.setAudio(uri, label) }
    fun setAudioKey(uri: String, key: String?) = write { preferences.setAudioKey(uri, key) }
    fun setAudioSettings(uri: String, value: com.phnem.vap.player.audio.AudioSettings) = write { preferences.setAudioSettings(uri, value) }
    fun setExternalAudio(uri: String, external: String?) = write { preferences.setExternalAudio(uri, external) }
    fun setSubtitles(uri: String, settings: com.phnem.vap.player.subtitles.SubtitleSettings) = write { preferences.setSubtitles(uri, settings) }
    fun removeSubtitle(uri: String, id: String) = write {
        val current = preferences.fileSettings(uri).first().subtitles
        preferences.setSubtitles(uri, current.copy(sources = current.sources.filter { it.id != id },
            selection = if (current.selection == id) "off" else current.selection, secondary = current.secondary?.takeIf { it != id }))
        com.phnem.vap.player.subtitles.SubtitleStorage(getApplication()).delete(id)
    }
    fun discoverSubtitles(uri: String) = write {
        val initial = preferences.fileSettings(uri).first().subtitles
        initial.sources.firstOrNull { it.id == initial.selection && it.format == "ass" && it.embeddedTrackId.isNotBlank() }?.let { source ->
            if (source.originStamp != com.phnem.vap.player.subtitles.SubtitleOrigin.stamp(getApplication(), uri)) {
                preferences.setSubtitles(uri, initial.copy(selection = "auto", sources = initial.sources.filter { it.embeddedTrackId.isBlank() }))
            }
        }
        if (!initial.discovered) {
            val storage = com.phnem.vap.player.subtitles.SubtitleStorage(getApplication())
            val found = storage.discover(android.net.Uri.parse(uri))
            val imported = found.mapNotNull { candidate ->
                try { storage.import(candidate) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            }
            val current = preferences.fileSettings(uri).first().subtitles
            val sources = (current.sources + imported).distinctBy { it.title }.take(32)
            preferences.setSubtitles(uri, current.copy(sources = sources, discovered = true,
                selection = if (current.selection == "auto") sources.firstOrNull()?.id ?: "auto" else current.selection))
        }
    }

    private fun write(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                android.util.Log.w("VapSettings", "Cannot save player preferences", error)
                saveFailures.emit(Unit)
            }
        }
    }
}
