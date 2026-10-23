package com.phnem.vap.player.video

import java.io.Closeable
import java.io.OutputStream

/** GIF89a, fixed RGB332 palette. Bounded 9-bit LZW blocks keep memory independent of duration. */
class GifWriter(private val out: OutputStream, private val width: Int, private val height: Int) : Closeable {
    init {
        require(width in 1..65535 && height in 1..65535)
        out.write("GIF89a".toByteArray(Charsets.US_ASCII)); word(width); word(height)
        out.write(byteArrayOf(0xf7.toByte(), 0, 0))
        repeat(256) { index ->
            out.write(((index shr 5) and 7) * 255 / 7)
            out.write(((index shr 2) and 7) * 255 / 7)
            out.write((index and 3) * 255 / 3)
        }
        out.write(byteArrayOf(0x21, 0xff.toByte(), 11))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }
    fun frame(pixels: IntArray, delayCentiseconds: Int) {
        require(pixels.size == width * height && delayCentiseconds in 1..65535)
        out.write(byteArrayOf(0x21, 0xf9.toByte(), 4, 4)); word(delayCentiseconds); word(0)
        out.write(0x2c); word(0); word(0); word(width); word(height); out.write(0)
        out.write(8) // Minimum LZW code size for the 256-color table.
        val blocks = ByteArray(255)
        var used = 0; var bits = 0; var buffer = 0
        fun byte(value: Int) {
            blocks[used++] = value.toByte()
            if (used == blocks.size) { out.write(used); out.write(blocks); used = 0 }
        }
        fun code(value: Int) {
            buffer = buffer or (value shl bits); bits += 9
            while (bits >= 8) { byte(buffer and 255); buffer = buffer ushr 8; bits -= 8 }
        }
        code(256)
        pixels.forEachIndexed { index, color ->
            if (index > 0 && index % 200 == 0) code(256)
            val r = ((color ushr 16 and 255) * 7 + 127) / 255
            val g = ((color ushr 8 and 255) * 7 + 127) / 255
            val b = ((color and 255) * 3 + 127) / 255
            code((r shl 5) or (g shl 2) or b)
        }
        code(257)
        if (bits > 0) byte(buffer and 255)
        if (used > 0) { out.write(used); out.write(blocks, 0, used) }
        out.write(0)
    }
    private fun word(value: Int) { out.write(value and 255); out.write(value ushr 8 and 255) }
    override fun close() { out.write(0x3b); out.flush() }
}
