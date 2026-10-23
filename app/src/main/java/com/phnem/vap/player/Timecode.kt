package com.phnem.vap.player

/** Секунды, mm:ss или hh:mm:ss. Переполнение и незаполненные части — ошибка, не seek к нулю. */
fun parseTimecode(value: String): Long? {
    val parts = value.trim().split(':')
    if (parts.size !in 1..3 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) return null
    val numbers = parts.map { it.toLongOrNull() ?: return null }
    if (numbers.drop(1).any { it >= 60L }) return null
    return try {
        var seconds = 0L
        numbers.forEach { seconds = Math.addExact(Math.multiplyExact(seconds, 60), it) }
        Math.multiplyExact(seconds, 1_000)
    } catch (_: ArithmeticException) { null }
}
