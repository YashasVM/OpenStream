package dev.openstream.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusCoordinateMapperTest {
    private val sensor = SensorRect(0, 0, 4_000, 3_000)

    @Test
    fun centerMapsToCenterOfCrop() {
        val crop = SensorRect(1_000, 750, 3_000, 2_250)

        val result = FocusCoordinateMapper.mapToMeteringRegion(0.5f, 0.5f, sensor, crop)

        assertTrue(2_000 in result.left..result.right)
        assertTrue(1_500 in result.top..result.bottom)
        assertTrue(result.left >= crop.left && result.right <= crop.right)
        assertTrue(result.top >= crop.top && result.bottom <= crop.bottom)
    }

    @Test
    fun rotationMapsDisplayTopLeftToSensorBottomLeft() {
        val result = FocusCoordinateMapper.mapToMeteringRegion(
            normalizedX = 0f,
            normalizedY = 0f,
            activeArray = sensor,
            rotationDegrees = 90,
        )

        assertEquals(0, result.left)
        assertEquals(3_000, result.bottom)
    }

    @Test
    fun frontMirrorFlipsHorizontalCoordinate() {
        val normal = FocusCoordinateMapper.mapToMeteringRegion(0.2f, 0.5f, sensor, mirrored = false)
        val mirrored = FocusCoordinateMapper.mapToMeteringRegion(0.2f, 0.5f, sensor, mirrored = true)

        assertTrue(normal.right < sensor.width / 2)
        assertTrue(mirrored.left > sensor.width / 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCoordinatesOutsideNormalizedFrame() {
        FocusCoordinateMapper.mapToMeteringRegion(1.1f, 0.5f, sensor)
    }
}
