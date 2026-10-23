package com.phnem.vap.cast.proxy

/** A single HTTP byte range; multipart and ambiguous ranges are rejected. */
data class CastRange(val start: Long, val length: Long?) {
    companion object {
        fun parse(header: String?, size: Long?): CastRange? {
            if (header == null) return CastRange(0, size)
            if (size == null || size <= 0) return null
            val match = Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(header.trim()) ?: return null
            val (first, last) = match.destructured
            if (first.isEmpty()) {
                val count = last.toLongOrNull()?.takeIf { it > 0 }?.coerceAtMost(size) ?: return null
                return CastRange(size - count, count)
            }
            val start = first.toLongOrNull()?.takeIf { it < size } ?: return null
            val end = if (last.isEmpty()) size - 1 else last.toLongOrNull()?.coerceAtMost(size - 1) ?: return null
            if (end < start) return null
            return CastRange(start, end - start + 1)
        }
    }
}
