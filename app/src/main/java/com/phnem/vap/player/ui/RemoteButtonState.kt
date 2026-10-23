package com.phnem.vap.player.ui

/**
 * Состояние кнопки «Воспроизвести на…» в доке плеера. Сама трансляция (Cast/DLNA) приезжает на
 * Этапе 7; тип лежит здесь, чтобы контролы переносились без правки.
 */
enum class RemoteButtonState { IDLE, DISCOVERING, CONNECTING, CONNECTED, ERROR }
