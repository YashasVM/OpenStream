package dev.openstream.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraEfficiencyTest {
    @Test
    fun streamingPrefersFixedTargetFpsWhileIdlePreviewPrefersLowestUpperBound() {
        val halRanges = listOf(
            12 to 30,
            15 to 15,
            15 to 24,
            15 to 30,
            30 to 30,
            15 to 60,
            60 to 60,
        )

        val streaming60 = chooseCaptureFpsRange(halRanges, targetFps = 60, streaming = true)
        val idle60 = chooseCaptureFpsRange(halRanges, targetFps = 60, streaming = false)
        val streaming30 = chooseCaptureFpsRange(halRanges, targetFps = 30, streaming = true)
        val idle30 = chooseCaptureFpsRange(halRanges, targetFps = 30, streaming = false)

        assertEquals(60 to 60, streaming60)
        assertEquals(15 to 15, idle60)
        assertEquals(30 to 30, streaming30)
        assertEquals(15 to 15, idle30)
    }

    @Test
    fun idlePreviewFallsBackTo30FpsWhenNoLowerUpperBoundIsAdvertised() {
        val thirtyOnly = listOf(12 to 30, 15 to 30, 30 to 30, 60 to 60)
        assertEquals(12 to 30, chooseCaptureFpsRange(thirtyOnly, targetFps = 60, streaming = false))

        val fixedOnly = listOf(30 to 30, 60 to 60)
        assertEquals(30 to 30, chooseCaptureFpsRange(fixedOnly, targetFps = 60, streaming = false))
        assertNull(chooseCaptureFpsRange(emptyList(), targetFps = 30, streaming = false))
    }

    @Test
    fun previewBufferSizePicksCameraSupportedSizeWithinCapAndMatchingAspect() {
        val supported = listOf(
            320 to 240,
            640 to 480,
            854 to 480,
            1280 to 720,
            1920 to 1080,
        )
        // 960x540 is NOT supported by this camera; must pick supported 854x480 (16:9 within 960 cap).
        assertEquals(854 to 480, choosePreviewBufferSize(supported, 1920, 1080))

        // When no size fits within cap, pick smallest supported matching aspect.
        val largeOnly = listOf(1280 to 720, 1920 to 1080, 1440 to 1080)
        assertEquals(1280 to 720, choosePreviewBufferSize(largeOnly, 1920, 1080))

        // When HAL reports no sizes, fall back to bounded even dimensions.
        val (wFallback, hFallback) = choosePreviewBufferSize(emptyList(), 1920, 1080)
        assertEquals(960, wFallback)
        assertEquals(540, hFallback)
        assertTrue(wFallback % 2 == 0 && hFallback % 2 == 0)
    }
}
