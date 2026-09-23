package dev.openstream.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureOutputsTest {
    @Test
    fun cameraKeepsEncoderOutputWhenActivityPreviewDetaches() {
        assertEquals(
            setOf(CameraCaptureOutput.Encoder),
            captureOutputKinds(hasPreview = false, hasEncoder = true),
        )
    }

    @Test
    fun cameraUsesPreviewAndEncoderTogetherWhileActivityIsVisible() {
        assertEquals(
            setOf(CameraCaptureOutput.Preview, CameraCaptureOutput.Encoder),
            captureOutputKinds(hasPreview = true, hasEncoder = true),
        )
    }

    @Test
    fun cameraHasNoCaptureSessionWhenNeitherOutputExists() {
        assertEquals(emptySet<CameraCaptureOutput>(), captureOutputKinds(hasPreview = false, hasEncoder = false))
    }
}
