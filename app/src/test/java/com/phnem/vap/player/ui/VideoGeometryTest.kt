package com.phnem.vap.player.ui

import org.junit.Assert.*
import org.junit.Test

class VideoGeometryTest {
    private fun frame(fit: VideoFit) = videoGeometry(2400, 1080, 320, 180, 1f, fit)
    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, 0.01f)

    @Test fun fitPreservesWholeFrame() {
        near(1920f, frame(VideoFit.FIT).width); near(1080f, frame(VideoFit.FIT).height)
    }
    @Test fun fillAndCropCoverWithoutChangingAspect() {
        near(2400f, frame(VideoFit.FILL).width); near(1350f, frame(VideoFit.FILL).height)
        near(3000f, frame(VideoFit.CROP).width); near(1687.5f, frame(VideoFit.CROP).height)
    }
    @Test fun stretchUsesBothViewportAxes() {
        near(2400f, frame(VideoFit.STRETCH).width); near(1080f, frame(VideoFit.STRETCH).height)
    }
    @Test fun originalUsesPhysicalPixelsAndSampleAspectRatio() {
        near(320f, frame(VideoFit.ORIGINAL).width); near(180f, frame(VideoFit.ORIGINAL).height)
        near(1440f, videoGeometry(2400, 1080, 720, 576, 2f, VideoFit.ORIGINAL).width)
    }
    @Test fun portraitAndUltrawideDoNotUseArbitraryCropCap() {
        val tall = videoGeometry(2400, 1080, 180, 320, 1f, VideoFit.FILL)
        near(2400f, tall.width); near(4266.667f, tall.height)
        val wide = videoGeometry(1080, 2400, 320, 180, 1f, VideoFit.FILL)
        near(4266.667f, wide.width); near(2400f, wide.height)
    }
    @Test fun zoomAnchorsAtTheFingers() {
        val next = transformVideo(VideoTransform(), frame(VideoFit.FIT), 2f, 0f, 0f, 1400f, 640f)
        near(2f, next.zoom); near(-200f, next.x); near(-100f, next.y)
    }
    @Test fun panClampsAtActualPictureEdges() {
        val f = frame(VideoFit.FIT)
        val next = transformVideo(VideoTransform(2f), f, 1f, 99999f, -99999f, 1200f, 540f)
        near(720f, next.x); near(-540f, next.y)
        val small = transformVideo(VideoTransform(), frame(VideoFit.ORIGINAL), 2f, 100f, 100f, 1200f, 540f)
        near(0f, small.x); near(0f, small.y)
    }
    @Test fun zoomBoundsAndInvalidInputStayFinite() {
        val f = frame(VideoFit.FIT)
        near(MAX_VIDEO_ZOOM, transformVideo(VideoTransform(), f, 100f, 0f, 0f, 0f, 0f).zoom)
        assertEquals(VideoTransform(), transformVideo(VideoTransform(2f, 100f, -100f), f, 0.01f, 0f, 0f, 1200f, 540f))
        assertEquals(VideoTransform(), transformVideo(VideoTransform(), f, Float.NaN, 0f, 0f, 0f, 0f))
    }
}
