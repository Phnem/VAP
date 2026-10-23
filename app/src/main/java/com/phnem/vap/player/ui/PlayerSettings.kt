package com.phnem.vap.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import java.util.Locale
import com.phnem.vap.player.video.DecoderMode
import com.phnem.vap.player.video.HdrOutput
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Настройки плеера — одна точка для экрана воспроизведения и экрана настроек.
 *
 * DataStore читается репозиторием PlayerPreferences, UI получает состояние из ViewModel.
 */
object PlayerSettingsKeys {
    /**
     * Включать следующее видео по концу текущего. По умолчанию ВКЛ (отсутствие ключа = `true`).
     */
    val AUTO_NEXT = booleanPreferencesKey("player_auto_next")
    val SEEK_STEP_SECONDS = intPreferencesKey("seek_step_seconds")
    val DOUBLE_TAP = booleanPreferencesKey("double_tap_seek")
    val SWIPE_SEEK = booleanPreferencesKey("swipe_seek")
    val SWIPE_BRIGHTNESS = booleanPreferencesKey("swipe_brightness")
    val SWIPE_VOLUME = booleanPreferencesKey("swipe_volume")
    val HOLD_SPEED = booleanPreferencesKey("hold_speed")
    val PINCH_ZOOM = booleanPreferencesKey("pinch_zoom")
    val DECODER = stringPreferencesKey("video_decoder")
    val HDR_OUTPUT = stringPreferencesKey("video_hdr_output")
    val MATCH_FPS = booleanPreferencesKey("video_match_fps")
    val ORIENTATION = stringPreferencesKey("orientation")
    val FRAME_PREVIEW = booleanPreferencesKey("frame_preview")
    val AUDIO_LANGUAGE = stringPreferencesKey("audio_language")
    val PRESERVE_PITCH = booleanPreferencesKey("audio_preserve_pitch")
}

data class PlayerSettings(
    val autoNext: Boolean = true,
    val seekStepSeconds: Int = 10,
    val doubleTapSeek: Boolean = true,
    val swipeSeek: Boolean = true,
    val swipeBrightness: Boolean = true,
    val swipeVolume: Boolean = true,
    val holdToSpeed: Boolean = true,
    val pinchZoom: Boolean = true,
    val framePreview: Boolean = true,
    val decoderMode: DecoderMode = DecoderMode.AUTO,
    val hdrOutput: HdrOutput = HdrOutput.AUTO,
    val matchFrameRate: Boolean = true,
    val orientation: PlayerOrientation = PlayerOrientation.LANDSCAPE,
    val preferredAudioLanguage: String = "",
    val preservePitch: Boolean = true,
)

fun Preferences.toPlayerSettings(): PlayerSettings = PlayerSettings(
    autoNext = this[PlayerSettingsKeys.AUTO_NEXT] ?: true,
    seekStepSeconds = (this[PlayerSettingsKeys.SEEK_STEP_SECONDS] ?: 10).coerceIn(1, 120),
    doubleTapSeek = this[PlayerSettingsKeys.DOUBLE_TAP] ?: true,
    swipeSeek = this[PlayerSettingsKeys.SWIPE_SEEK] ?: true,
    swipeBrightness = this[PlayerSettingsKeys.SWIPE_BRIGHTNESS] ?: true,
    swipeVolume = this[PlayerSettingsKeys.SWIPE_VOLUME] ?: true,
    holdToSpeed = this[PlayerSettingsKeys.HOLD_SPEED] ?: true,
    pinchZoom = this[PlayerSettingsKeys.PINCH_ZOOM] ?: true,
    framePreview = this[PlayerSettingsKeys.FRAME_PREVIEW] ?: true,
    decoderMode = DecoderMode.entries.firstOrNull { it.name == this[PlayerSettingsKeys.DECODER] } ?: DecoderMode.AUTO,
    hdrOutput = HdrOutput.entries.firstOrNull { it.name == this[PlayerSettingsKeys.HDR_OUTPUT] } ?: HdrOutput.AUTO,
    matchFrameRate = this[PlayerSettingsKeys.MATCH_FPS] ?: true,
    orientation = PlayerOrientation.entries.firstOrNull { it.name == this[PlayerSettingsKeys.ORIENTATION] } ?: PlayerOrientation.LANDSCAPE,
    preferredAudioLanguage = this[PlayerSettingsKeys.AUDIO_LANGUAGE] ?: "",
    preservePitch = this[PlayerSettingsKeys.PRESERVE_PITCH] ?: true,
)

/**
 * Язык подписей плеера. Пока переключателя языка в VAP нет, это язык системы; когда он появится,
 * его значение подставляет активити плеера.
 */
val LocalPlayerRussian = staticCompositionLocalOf { Locale.getDefault().language == "ru" }

@Composable
@ReadOnlyComposable
internal fun playerIsRu(): Boolean = LocalPlayerRussian.current
