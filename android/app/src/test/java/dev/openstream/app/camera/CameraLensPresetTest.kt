package dev.openstream.app.camera

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraLensPresetTest {
    @Test
    fun formatsRoundedOpticalZoomLabels() {
        assertEquals("0.6×", formatZoomLabel(0.56f))
        assertEquals("1×", formatZoomLabel(0.99f))
        assertEquals("3×", formatZoomLabel(2.74f))
        assertEquals("3×", formatZoomLabel(2.98f))
        assertEquals("5×", formatZoomLabel(4.96f))
    }

    @Test
    fun buildsSortedPresetsOnTheLogicalCameraAndFiltersUnsupportedRatios() {
        val defaultScale = 6.3f / 9.8f
        val physical = listOf(0.56f, 1f, 2.98f, 4.96f, 12f).mapIndexed { index, ratio ->
            PhysicalLensInfo("physical-$index", defaultScale * ratio, 1f)
        }

        val presets = buildOpticalLensPresets(
            logicalCameraId = "0",
            defaultFocalLengthMm = 6.3f,
            defaultSensorWidthMm = 9.8f,
            minZoomRatio = 0.5f,
            maxZoomRatio = 10f,
            physicalLenses = physical,
        )

        assertEquals(listOf("0.6×", "1×", "3×", "5×"), presets.map { it.shortLabel })
        assertEquals(listOf("0", "0", "0", "0"), presets.map { it.cameraId })
        assertEquals(
            listOf(
                CameraCharacteristics.LENS_FACING_BACK,
                CameraCharacteristics.LENS_FACING_BACK,
                CameraCharacteristics.LENS_FACING_BACK,
                CameraCharacteristics.LENS_FACING_BACK,
            ),
            presets.map { it.facing },
        )
    }
}
