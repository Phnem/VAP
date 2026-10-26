package com.phnem.vap.player.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.phnem.vap.player.ui.PlayerSettings
import com.phnem.vap.player.ui.PlayerSettingsKeys
import com.phnem.vap.player.ui.VideoFit
import com.phnem.vap.player.ui.toPlayerSettings
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import com.phnem.vap.player.video.PictureSettings
import java.security.MessageDigest

// Один экземпляр DataStore на файл для сервиса и всех экранов.
private val Context.playbackPreferences by preferencesDataStore(name = "player_settings")

data class FilePlaybackSettings(
    val speed: Float = 1f,
    val fit: VideoFit = VideoFit.FIT,
    val audioLabel: String? = null,
    val picture: PictureSettings = PictureSettings(),
    val audioKey: String? = null,
    val audio: com.phnem.vap.player.audio.AudioSettings = com.phnem.vap.player.audio.AudioSettings(),
    val subtitles: com.phnem.vap.player.subtitles.SubtitleSettings = com.phnem.vap.player.subtitles.SubtitleSettings(),
)

class PlayerPreferences(context: Context) {
    private val store = context.applicationContext.playbackPreferences
    private val data = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }
    val settings = data.map { it.toPlayerSettings() }.distinctUntilChanged()

    fun fileSettings(uri: String) = data.map { prefs ->
        val key = fileKey(uri)
        FilePlaybackSettings(
            subtitles = com.phnem.vap.player.subtitles.SubtitleSettingsJson.decode(prefs[stringPreferencesKey("${key}_subtitles")]),
            speed = (prefs[floatPreferencesKey("${key}_speed")] ?: 1f).let {
                if (it.isFinite()) it.coerceIn(0.25f, 4f) else 1f
            },
            fit = prefs[stringPreferencesKey("${key}_frame_mode")]?.let { saved ->
                VideoFit.entries.firstOrNull { it.name == saved }
            } ?: if (prefs[stringPreferencesKey("${key}_fit")] == "CROP") VideoFit.FILL else VideoFit.FIT,
            audioLabel = prefs[stringPreferencesKey("${key}_audio")],
            audioKey = prefs[stringPreferencesKey("${key}_audio_key")],
            audio = com.phnem.vap.player.audio.AudioSettings(
                delayMs = prefs[intPreferencesKey("${key}_audio_delay")] ?: 0,
                gainDb = prefs[floatPreferencesKey("${key}_audio_gain")] ?: 0f,
                balance = prefs[floatPreferencesKey("${key}_audio_balance")] ?: 0f,
                mono = prefs[booleanPreferencesKey("${key}_audio_mono")] ?: false,
                normalize = prefs[booleanPreferencesKey("${key}_audio_normalize")] ?: false,
                equalizer = List(5) { prefs[floatPreferencesKey("${key}_eq_$it")] ?: 0f },
                externalUri = prefs[stringPreferencesKey("${key}_external_audio")],
            ).sanitized(),
            picture = PictureSettings(
                prefs[floatPreferencesKey("${key}_brightness")] ?: 0f,
                prefs[floatPreferencesKey("${key}_contrast")] ?: 1f,
                prefs[floatPreferencesKey("${key}_saturation")] ?: 1f,
                prefs[floatPreferencesKey("${key}_gamma")] ?: 1f,
            ).sanitized(),
        )
    }.distinctUntilChanged()

    suspend fun updateSettings(update: (PlayerSettings) -> PlayerSettings) {
        store.edit { prefs ->
            val value = update(prefs.toPlayerSettings())
            prefs[PlayerSettingsKeys.AUTO_NEXT] = value.autoNext
            prefs[PlayerSettingsKeys.SEEK_STEP_SECONDS] = value.seekStepSeconds.coerceIn(1, 120)
            prefs[PlayerSettingsKeys.DOUBLE_TAP] = value.doubleTapSeek
            prefs[PlayerSettingsKeys.SWIPE_SEEK] = value.swipeSeek
            prefs[PlayerSettingsKeys.SWIPE_BRIGHTNESS] = value.swipeBrightness
            prefs[PlayerSettingsKeys.SWIPE_VOLUME] = value.swipeVolume
            prefs[PlayerSettingsKeys.HOLD_SPEED] = value.holdToSpeed
            prefs[PlayerSettingsKeys.PINCH_ZOOM] = value.pinchZoom
            prefs[PlayerSettingsKeys.FRAME_PREVIEW] = value.framePreview
            prefs[PlayerSettingsKeys.ORIENTATION_MODE] = value.orientation.name
            prefs[PlayerSettingsKeys.DECODER] = value.decoderMode.name
            prefs[PlayerSettingsKeys.HDR_OUTPUT] = value.hdrOutput.name
            prefs[PlayerSettingsKeys.MATCH_FPS] = value.matchFrameRate
            prefs[PlayerSettingsKeys.AUDIO_LANGUAGE] = value.preferredAudioLanguage.trim().take(35)
            prefs[PlayerSettingsKeys.PRESERVE_PITCH] = value.preservePitch
        }
    }

    suspend fun setSpeed(uri: String, speed: Float) {
        require(speed.isFinite() && speed in 0.25f..4f)
        store.edit { it[floatPreferencesKey("${fileKey(uri)}_speed")] = speed }
    }

    suspend fun setFit(uri: String, fit: VideoFit) {
        store.edit { it[stringPreferencesKey("${fileKey(uri)}_frame_mode")] = fit.name }
    }

    suspend fun setPicture(uri: String, settings: PictureSettings) {
        val key = fileKey(uri)
        val value = settings.sanitized()
        store.edit {
            it[floatPreferencesKey("${key}_brightness")] = value.brightness
            it[floatPreferencesKey("${key}_contrast")] = value.contrast
            it[floatPreferencesKey("${key}_saturation")] = value.saturation
            it[floatPreferencesKey("${key}_gamma")] = value.gamma
        }
    }

    suspend fun setAudio(uri: String, label: String) {
        store.edit { it[stringPreferencesKey("${fileKey(uri)}_audio")] = label }
    }

    suspend fun setAudioKey(uri: String, selection: String?) {
        store.edit { val key = stringPreferencesKey("${fileKey(uri)}_audio_key")
            if (selection == null) it.remove(key) else it[key] = selection
            it.remove(stringPreferencesKey("${fileKey(uri)}_audio")) }
    }

    suspend fun setExternalAudio(uri: String, external: String?) {
        require(external == null || android.net.Uri.parse(external).scheme in setOf("content", "file"))
        store.edit { val key = stringPreferencesKey("${fileKey(uri)}_external_audio")
            if (external == null) it.remove(key) else it[key] = external
            it.remove(stringPreferencesKey("${fileKey(uri)}_audio_key"))
            it.remove(stringPreferencesKey("${fileKey(uri)}_audio")) }
    }

    suspend fun setAudioSettings(uri: String, settings: com.phnem.vap.player.audio.AudioSettings) {
        val key = fileKey(uri); val value = settings.sanitized()
        store.edit { prefs ->
            prefs[intPreferencesKey("${key}_audio_delay")] = value.delayMs
            prefs[floatPreferencesKey("${key}_audio_gain")] = value.gainDb
            prefs[floatPreferencesKey("${key}_audio_balance")] = value.balance
            prefs[booleanPreferencesKey("${key}_audio_mono")] = value.mono
            prefs[booleanPreferencesKey("${key}_audio_normalize")] = value.normalize
            value.equalizer.forEachIndexed { i, db -> prefs[floatPreferencesKey("${key}_eq_$i")] = db }
            val external = stringPreferencesKey("${key}_external_audio")
            if (value.externalUri == null) prefs.remove(external) else prefs[external] = value.externalUri
        }
    }

    suspend fun resetFile(uri: String) {
        val key = fileKey(uri)
        store.edit {
            it.remove(stringPreferencesKey("${key}_subtitles"))
            it.remove(floatPreferencesKey("${key}_speed"))
            it.remove(stringPreferencesKey("${key}_fit"))
            it.remove(stringPreferencesKey("${key}_frame_mode"))
            it.remove(stringPreferencesKey("${key}_audio"))
            it.remove(stringPreferencesKey("${key}_audio_key"))
            it.remove(stringPreferencesKey("${key}_external_audio"))
            it.remove(intPreferencesKey("${key}_audio_delay"))
            listOf("gain", "balance").forEach { field -> it.remove(floatPreferencesKey("${key}_audio_$field")) }
            listOf("mono", "normalize").forEach { field -> it.remove(booleanPreferencesKey("${key}_audio_$field")) }
            repeat(5) { i -> it.remove(floatPreferencesKey("${key}_eq_$i")) }
            listOf("brightness", "contrast", "saturation", "gamma").forEach { field -> it.remove(floatPreferencesKey("${key}_$field")) }
        }
    }

    private fun fileKey(uri: String): String = "file_" + MessageDigest.getInstance("SHA-256")
        .digest(uri.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    suspend fun setSubtitles(uri: String, settings: com.phnem.vap.player.subtitles.SubtitleSettings) {
        store.edit { it[stringPreferencesKey("${fileKey(uri)}_subtitles")] = com.phnem.vap.player.subtitles.SubtitleSettingsJson.encode(settings.sanitized()) }
    }
}
