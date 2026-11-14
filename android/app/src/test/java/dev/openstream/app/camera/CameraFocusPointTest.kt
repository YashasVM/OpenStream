package dev.openstream.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraFocusPointTest {
    @Test fun portraitBackRotatesPreviewIntoSensorCoordinates() {
        assertEquals(.2f to .9f, previewFocusPoint(.1f, .2f, 90, 0, false))
        assertEquals(.1f to .2f, previewFocusPoint(.1f, .2f, 90, 90, false))
        assertEquals(.8f to .1f, previewFocusPoint(.1f, .2f, 90, 180, false))
    }
    @Test fun frontCameraMirrorsBeforeInverseRotation() {
        assertEquals(.8f to .9f, previewFocusPoint(.1f, .2f, 270, 0, true))
        assertEquals(.9f to .2f, previewFocusPoint(.1f, .2f, 270, 90, true))
    }
    @Test fun rejectsCoordinatesOutsideThePreviewAndNonFiniteValues() {
        assertNull(previewFocusPoint(-.1f, .5f, 90, 0, false))
        assertNull(previewFocusPoint(.5f, Float.NaN, 90, 0, false))
        assertNull(previewFocusPoint(Float.POSITIVE_INFINITY, .5f, 90, 0, false))
    }
}
