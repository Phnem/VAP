package com.phnem.vap.player

import androidx.compose.runtime.mutableStateOf

/**
 * Кто сейчас показывает кадр. Только главный поток.
 *
 * Плеер и мини-окно библиотеки смотрят на один ExoPlayer через разные MediaController, а поверхность
 * у него одна. `PlayerView.player = null` у любого из них обнуляет её целиком, даже если кадр уже
 * забрал другой: опоздавший `onStop` библиотеки оставлял плеер с застывшим кадром, а системный PiP —
 * чёрным. Поэтому поверхность передаётся явно: новый владелец сначала отцепляет прежнего.
 */
object VideoOutput {
    private var owner: Any? = null
    private var release: (() -> Unit)? = null
    private var playerScreens = 0

    /** Экран плеера виден — полноэкранный или в системном PiP. Мини-окно на это время прячется. */
    val playerOnScreen = mutableStateOf(false)

    /** [release] отцепляет вид владельца от плеера; зовётся, когда кадр забирает другой. */
    fun claim(owner: Any, release: () -> Unit) {
        if (this.owner !== owner) this.release?.invoke()
        this.owner = owner
        this.release = release
    }

    fun drop(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        release = null
    }

    fun playerStarted() {
        playerScreens++
        playerOnScreen.value = true
    }

    fun playerStopped() {
        playerScreens = (playerScreens - 1).coerceAtLeast(0)
        playerOnScreen.value = playerScreens > 0
    }
}
