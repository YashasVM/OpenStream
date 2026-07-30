package dev.openstream.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStateStoreTest {
    @Test
    fun applyingPatchPreservesOtherSettingsAndAdvancesRevision() {
        val store = CameraStateStore(capabilities())

        val first = store.applySettings(
            expectedRevision = 0,
            actor = CameraActor.Camera,
            patch = CameraSettingsPatch(zoomRatio = 2f, torch = true),
        )
        assertTrue(first is CameraControlResult.Applied)
        val second = store.applySettings(
            expectedRevision = 1,
            actor = CameraActor.Obs,
            patch = CameraSettingsPatch(exposureCompensation = 2),
        )

        assertTrue(second is CameraControlResult.Applied)
        val state = store.snapshot()
        assertEquals(2L, state.revision)
        assertEquals(CameraActor.Obs, state.lastActor)
        assertEquals(2f, state.settings.zoomRatio)
        assertTrue(state.settings.torch)
        assertEquals(2, state.settings.exposureCompensation)
    }

    @Test
    fun staleRevisionReturnsCurrentStateWithoutMutation() {
        val store = CameraStateStore(capabilities())
        store.applySettings(0, CameraActor.Obs, CameraSettingsPatch(zoomRatio = 2f))

        val result = store.applySettings(0, CameraActor.Obs, CameraSettingsPatch(torch = true))

        assertTrue(result is CameraControlResult.Conflict)
        assertEquals(1L, store.snapshot().revision)
        assertEquals(false, store.snapshot().settings.torch)
    }

    @Test
    fun obsLockRejectsCameraButAllowsObs() {
        val store = CameraStateStore(capabilities())
        store.setAuthority(0, CameraActor.Obs, AuthorityMode.ObsLock)

        val local = store.applySettings(1, CameraActor.Camera, CameraSettingsPatch(zoomRatio = 2f))
        val remote = store.applySettings(1, CameraActor.Obs, CameraSettingsPatch(zoomRatio = 2f))

        assertTrue(local is CameraControlResult.Locked)
        assertTrue(remote is CameraControlResult.Applied)
        assertEquals(2f, store.snapshot().settings.zoomRatio)
    }

    @Test
    fun unsupportedManualControlsReturnFieldSpecificError() {
        val store = CameraStateStore(capabilities(manual = false))

        val result = store.applySettings(
            0,
            CameraActor.Obs,
            CameraSettingsPatch(exposureMode = ExposureMode.Manual),
        )

        assertTrue(result is CameraControlResult.Unsupported)
        assertEquals("exposureMode", (result as CameraControlResult.Unsupported).field)
        assertEquals(0L, store.snapshot().revision)
    }

    @Test
    fun tallyMakesProgramAuthoritativeOverPreview() {
        val store = CameraStateStore(capabilities())

        val state = store.setTally(program = true, preview = true)

        assertTrue(state.tally.program)
        assertEquals(false, state.tally.preview)
        assertEquals(1L, state.revision)
    }

    @Test
    fun zoomTransitionRecordsTargetAndAdvancesRevisionOnlyOnce() {
        val store = CameraStateStore(capabilities())

        val result = store.startZoomTransition(0, CameraActor.Obs, 3f, 2_000)

        assertTrue(result is CameraControlResult.Applied)
        assertEquals(1L, store.snapshot().revision)
        assertEquals(3f, store.snapshot().settings.zoomRatio)
        assertEquals(3f, store.snapshot().zoomTransition?.targetRatio)
        assertEquals(2_000, store.snapshot().zoomTransition?.durationMs)
        store.cancelZoomTransition()
        assertEquals(1L, store.snapshot().revision)
        assertEquals(null, store.snapshot().zoomTransition)
    }

    @Test
    fun zoomTransitionRejectsBadDurationAndNonFiniteRatio() {
        val store = CameraStateStore(capabilities())

        val badDuration = store.startZoomTransition(0, CameraActor.Obs, 2f, 100)
        val badRatio = store.startZoomTransition(0, CameraActor.Obs, Float.NaN, 2_000)

        assertTrue(badDuration is CameraControlResult.Invalid)
        assertTrue(badRatio is CameraControlResult.Invalid)
        assertEquals(0L, store.snapshot().revision)
    }

    @Test
    fun localZoomPatchCancelsTransitionWithoutASecondTransitionRevision() {
        val store = CameraStateStore(capabilities())
        store.startZoomTransition(0, CameraActor.Obs, 3f, 2_000)

        val result = store.applySettings(1, CameraActor.Camera, CameraSettingsPatch(zoomRatio = 2f))

        assertTrue(result is CameraControlResult.Applied)
        assertEquals(2L, store.snapshot().revision)
        assertEquals(2f, store.snapshot().settings.zoomRatio)
        assertEquals(null, store.snapshot().zoomTransition)
    }

    @Test
    fun newerTransitionReplacesPendingTargetFromCurrentRevision() {
        val store = CameraStateStore(capabilities())
        store.startZoomTransition(0, CameraActor.Obs, 3f, 2_000)

        val replacement = store.startZoomTransition(1, CameraActor.Obs, 5f, 500)

        assertTrue(replacement is CameraControlResult.Applied)
        assertEquals(2L, store.snapshot().revision)
        assertEquals(5f, store.snapshot().settings.zoomRatio)
        assertEquals(5f, store.snapshot().zoomTransition?.targetRatio)
        assertEquals(500, store.snapshot().zoomTransition?.durationMs)
    }

    @Test
    fun easingStartsEndsAndIsEaseInOut() {
        assertEquals(0f, Camera2Controller.easeInOutProgress(0f))
        assertEquals(1f, Camera2Controller.easeInOutProgress(1f))
        assertEquals(0.5f, Camera2Controller.easeInOutProgress(0.5f))
        assertTrue(Camera2Controller.easeInOutProgress(0.25f) < 0.25f)
        assertTrue(Camera2Controller.easeInOutProgress(0.75f) > 0.75f)
    }

    private fun capabilities(manual: Boolean = true) = CameraCapabilities(
        cameraId = "0",
        displayName = "Back camera system",
        lensFacing = "back",
        logicalMultiCamera = true,
        physicalCameraIds = listOf("2", "3"),
        manualSensor = manual,
        manualWhiteBalance = manual,
        supportsAwbLock = true,
        supportsTapFocus = true,
        supportsAeRegions = true,
        supportsTorch = true,
        supportsZoomRatio = true,
        isoRange = if (manual) IntValueRange(50, 3200) else null,
        shutterRangeNs = if (manual) LongValueRange(100_000, 1_000_000_000) else null,
        exposureCompensationRange = IntValueRange(-4, 4),
        focusDistanceRange = FloatValueRange(0f, 10f),
        zoomRange = FloatValueRange(0.5f, 10f),
        fpsRanges = listOf(IntValueRange(24, 30), IntValueRange(60, 60)),
        focusModes = FocusMode.entries.toSet(),
        whiteBalanceModes = WhiteBalanceMode.entries.toSet(),
        stabilizationModes = StabilizationMode.entries.toSet(),
    )
}
