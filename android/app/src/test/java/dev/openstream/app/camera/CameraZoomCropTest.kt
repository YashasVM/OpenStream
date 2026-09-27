package dev.openstream.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraZoomCropTest {
    @Test
    fun cropUsesTheSensorActiveArrayOriginAndCentersAtRequestedZoom() {
        val crop = centeredCropRegion(100, 200, 4100, 3200, 2f)

        assertEquals(CameraCropRegion(1100, 950, 3100, 2450), crop)
    }

    @Test
    fun oneTimesZoomKeepsTheFullActiveArray() {
        val sensor = CameraCropRegion(24, 36, 4024, 3036)

        assertEquals(sensor, centeredCropRegion(sensor.left, sensor.top, sensor.right, sensor.bottom, 1f))
    }
}
