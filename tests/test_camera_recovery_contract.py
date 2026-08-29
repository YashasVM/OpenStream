from pathlib import Path


SOURCE = Path(
    "android/app/src/main/java/dev/openstream/app/camera/Camera2Controller.kt"
).read_text()


def _block_after(source: str, marker: str) -> str:
    start = source.index(marker)
    brace = source.index("{", start)
    depth = 0
    for index in range(brace, len(source)):
        char = source[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[brace + 1 : index]
    raise AssertionError(f"unterminated block after {marker!r}")


def test_transient_camera_loss_waits_for_availability_before_reopening():
    start_preview = _block_after(SOURCE, "fun startPreview()")
    disconnected = _block_after(start_preview, "override fun onDisconnected(device: CameraDevice)")
    errored = _block_after(start_preview, "override fun onError(device: CameraDevice, error: Int)")
    recovery = _block_after(SOURCE, "private fun watchForCameraAvailability(cameraId: String)")
    available = _block_after(recovery, "override fun onCameraAvailable(availableCameraId: String)")

    assert "closeCamera()" in disconnected
    assert "watchForCameraAvailability(desiredId)" in disconnected
    assert "ERROR_CAMERA_DISABLED" in errored
    assert "watchForCameraAvailability(desiredId)" in errored
    assert "registerAvailabilityCallback(callback, handler)" in recovery

    assert "availableCameraId != cameraId" in available
    assert "!desiredRunning" in available
    assert "cameraGeneration.get() != generation" in available
    assert "activeCameraId != cameraId" in available
    assert "previewSurfaceProvider().isValid" in available
    assert available.index("cancelCameraRecovery()") < available.index("startPreview()")


def test_stop_cancels_recovery_before_invalidating_camera_generation():
    stop = _block_after(SOURCE, "fun stop()")
    assert stop.index("desiredRunning = false") < stop.index("cancelCameraRecovery()")
    assert stop.index("cancelCameraRecovery()") < stop.index("closeCamera()")


def test_open_failures_do_not_crash_and_permission_revocation_does_not_retry():
    start_preview = _block_after(SOURCE, "fun startPreview()")
    security = start_preview.index("catch (security: SecurityException)")
    generic = start_preview.index("catch (error: Exception)")

    assert security < generic
    security_block = _block_after(start_preview, "catch (security: SecurityException)")
    generic_block = _block_after(start_preview, "catch (error: Exception)")
    assert "watchForCameraAvailability" not in security_block
    assert "watchForCameraAvailability(desiredId)" in generic_block


def test_capture_session_sync_failure_recovers_only_current_camera():
    create_session = _block_after(SOURCE, "private fun createSession()")
    assert "catch (error: IllegalStateException)" in create_session
    assert "catch (error: CameraAccessException)" in create_session
    assert create_session.count("recoverFromSessionCreationFailure(device, generation, error)") == 2

    recovery = _block_after(
        SOURCE,
        "private fun recoverFromSessionCreationFailure(device: CameraDevice, generation: Long, error: Exception)",
    )
    guard = "sessionGeneration.get() != generation || camera !== device"
    assert guard in recovery
    assert recovery.index(guard) < recovery.index("closeCamera()")
    assert recovery.index("closeCamera()") < recovery.index("watchForCameraAvailability(cameraId)")
