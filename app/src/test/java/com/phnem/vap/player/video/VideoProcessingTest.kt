package com.phnem.vap.player.video

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class VideoProcessingTest {
    @Test fun gifDecodesAcrossClearCodesAndSubBlocks() {
        val bytes = ByteArrayOutputStream()
        GifWriter(bytes, 257, 3).use { writer ->
            writer.frame(IntArray(771) { if (it % 2 == 0) 0xffff0000.toInt() else 0xff0000ff.toInt() }, 12)
            writer.frame(IntArray(771) { 0xff00ff00.toInt() }, 13)
        }
        // Android unit compilation hides java.desktop APIs; use the JDK decoder via reflection.
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val readerApi = Class.forName("javax.imageio.ImageReader")
        val bufferedImage = Class.forName("java.awt.image.BufferedImage")
        val readers = imageIo.getMethod("getImageReadersByFormatName", String::class.java).invoke(null, "gif") as Iterator<*>
        val reader = readers.next()!!
        val input = imageIo.getMethod("createImageInputStream", Any::class.java).invoke(null, bytes.toByteArray().inputStream())
        (input as java.io.Closeable).use {
            readerApi.getMethod("setInput", Any::class.java).invoke(reader, input)
            assertEquals(2, readerApi.getMethod("getNumImages", Boolean::class.javaPrimitiveType).invoke(reader, true))
            val first = readerApi.getMethod("read", Int::class.javaPrimitiveType).invoke(reader, 0)
            assertEquals(257, bufferedImage.getMethod("getWidth").invoke(first))
            assertEquals(3, bufferedImage.getMethod("getHeight").invoke(first))
            val rgb = bufferedImage.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            for (i in 0 until 771) assertEquals(if (i % 2 == 0) 0xffff0000.toInt() else 0xff0000ff.toInt(), rgb.invoke(first, i % 257, i / 257))
            val second = readerApi.getMethod("read", Int::class.javaPrimitiveType).invoke(reader, 1)
            assertEquals(0xff00ff00.toInt(), rgb.invoke(second, 256, 2))
        }
        readerApi.getMethod("dispose").invoke(reader)
    }
    @Test fun gifRejectsInconsistentFrameSize() {
        GifWriter(ByteArrayOutputStream(), 3, 2).use { writer ->
            assertThrows(IllegalArgumentException::class.java) { writer.frame(IntArray(5), 12) }
        }
    }
    @Test fun exportBoundsRejectOverflowAndReverseRanges() {
        assertTrue(ExportRange(5_000, 15_000).valid(120_000, 15_000))
        for (range in listOf(ExportRange(-1, 5), ExportRange(5, 5), ExportRange(9, 4), ExportRange(1, Long.MAX_VALUE), ExportRange(0, 16_000)))
            assertFalse(range.valid(120_000, 15_000))
    }
    @Test fun pictureSettingsClampNonFiniteInput() {
        assertEquals(PictureSettings(), PictureSettings(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, Float.NEGATIVE_INFINITY).sanitized())
        assertEquals(PictureSettings(0.5f, 0.5f, 2f, 2.5f), PictureSettings(3f, 0f, 5f, 9f).sanitized())
    }
    @Test fun neutralCorrectionKeepsRgb() {
        val result = adjustRgb(0.1f, 0.5f, 0.9f, PictureSettings())
        assertArrayEquals(floatArrayOf(0.1f, 0.5f, 0.9f), result, 0.00001f)
    }
    @Test fun saturationZeroAndGammaBehaveAsLabeled() {
        val grey = adjustRgb(1f, 0f, 0f, PictureSettings(saturation = 0f))
        assertEquals(grey[0], grey[1], 0f); assertEquals(grey[1], grey[2], 0f)
        assertTrue(adjustRgb(0.25f, 0.25f, 0.25f, PictureSettings(gamma = 2f))[0] > 0.25f)
    }
    @Test fun correctionClampsWhiteAndBlack() {
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), adjustRgb(1f, 1f, 1f, PictureSettings(brightness = 0.5f)), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), adjustRgb(0f, 0f, 0f, PictureSettings(brightness = -0.5f)), 0f)
    }
    @Test fun recoveryNeverRepeatsCodecOrExceedsBudget() {
        val budget = DecoderRetryBudget(2)
        assertFalse(budget.exclude(null)); assertTrue(budget.exclude("hardware")); assertFalse(budget.exclude("hardware"))
        assertTrue(budget.exclude("software")); assertFalse(budget.exclude("third"))
        assertEquals(setOf("hardware", "software"), budget.excluded())
        budget.reset(); assertTrue(budget.exclude("hardware"))
    }
}
