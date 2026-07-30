package dev.openstream.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLensDiscoveryTest {
    @Test
    fun `logical multi camera exposes its detected focal ratios`() {
        val lenses = CameraLensDiscovery.rearLenses(
            logicalCameraId = "0",
            candidates = listOf(
                CameraLensCandidate("uw", 13.2f),
                CameraLensCandidate("main", 24f),
                CameraLensCandidate("tele3", 72f),
                CameraLensCandidate("tele5", 120f),
            ),
            supportsLogicalZoomRatio = true,
        )

        assertEquals(listOf("0.6×", "1×", "3×", "5×"), lenses.map { it.shortLabel })
        assertTrue(lenses.all { it.cameraId == "0" })
        assertEquals(5f, lenses.last().targetZoom, 0.01f)
    }

    @Test
    fun `single rear camera receives one and five times digital shortcuts`() {
        val lenses = CameraLensDiscovery.rearLenses(
            logicalCameraId = null,
            candidates = listOf(CameraLensCandidate("back", 24f)),
            supportsLogicalZoomRatio = false,
        )

        assertEquals(listOf("1×", "5×"), lenses.map { it.shortLabel })
        assertEquals(listOf(1f, 5f), lenses.map { it.targetZoom })
        assertTrue(lenses.all { it.cameraId == "back" })
    }

    @Test
    fun `pre android eleven logical camera falls back to digital shortcuts`() {
        val lenses = CameraLensDiscovery.rearLenses(
            logicalCameraId = "0",
            candidates = listOf(
                CameraLensCandidate("uw", 13.2f),
                CameraLensCandidate("main", 24f),
                CameraLensCandidate("tele", 120f),
            ),
            supportsLogicalZoomRatio = false,
        )

        assertEquals(listOf("1×", "5×"), lenses.map { it.shortLabel })
        assertTrue(lenses.all { it.cameraId == "0" })
    }
}
