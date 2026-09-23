from pathlib import Path

from _helpers import block_after


SOURCE = Path(
    "android/app/src/main/java/dev/openstream/app/camera/Camera2Controller.kt"
).read_text()


def test_transient_camera_loss_waits_for_availability_before_reopening():
    start_preview = block_after(SOURCE, "private fun startPreviewLocked()")
    disconnected = block_after(start_preview, "override fun onDisconnected(device: CameraDevice)")
    errored = block_after(start_preview, "override fun onError(device: CameraDevice, error: Int)")
    recovery = block_after(SOURCE, "private fun watchForCameraAvailability(cameraId: String)")
    available = block_after(recovery, "override fun onCameraAvailable(availableCameraId: String)")

    assert "closeCamera()" in disconnected
    assert "watchForCameraAvailability(desiredId)" in disconnected
    assert "ERROR_CAMERA_DISABLED" in errored
    assert "watchForCameraAvailability(desiredId)" in errored
    assert "registerAvailabilityCallback(callback, handler)" in recovery

    recovery_lock = block_after(available, "synchronized(lifecycleLock)")
    assert "availableCameraId != cameraId" in recovery_lock
    assert "!desiredRunning" in recovery_lock
    assert "lifecycleGeneration != expectedLifecycleGeneration" in recovery_lock
    assert "cameraGeneration.get() != cameraRecoveryGeneration" in recovery_lock
    assert "activeCameraId != cameraId" in recovery_lock
    assert "previewSurface?.isValid == true" in recovery_lock
    assert recovery_lock.index("cancelCameraRecoveryLocked()") < recovery_lock.index(
        "startPreviewLocked()"
    )


def test_stop_and_recovery_restart_share_lifecycle_lock_and_generation():
    start = block_after(SOURCE, "fun startPreview()")
    start_lock = block_after(start, "synchronized(lifecycleLock)")
    assert "lifecycleGeneration += 1" in start_lock
    assert start_lock.index("desiredRunning = true") < start_lock.index("startPreviewLocked()")

    stop = block_after(SOURCE, "fun stop()")
    stop_lock = block_after(stop, "synchronized(lifecycleLock)")
    assert stop_lock.index("desiredRunning = false") < stop_lock.index("lifecycleGeneration += 1")
    assert stop_lock.index("lifecycleGeneration += 1") < stop_lock.index(
        "cancelCameraRecoveryLocked()"
    )
    assert stop_lock.index("cancelCameraRecoveryLocked()") < stop_lock.index("closeCamera()")

    recovery = block_after(SOURCE, "private fun watchForCameraAvailability(cameraId: String)")
    available = block_after(recovery, "override fun onCameraAvailable(availableCameraId: String)")
    recovery_lock = block_after(available, "synchronized(lifecycleLock)")
    assert "lifecycleGeneration != expectedLifecycleGeneration" in recovery_lock
    assert recovery_lock.index("lifecycleGeneration != expectedLifecycleGeneration") < recovery_lock.index(
        "startPreviewLocked()"
    )
    assert "startPreview()" not in recovery_lock


def test_open_failures_do_not_crash_and_permission_revocation_does_not_retry():
    start_preview = block_after(SOURCE, "private fun startPreviewLocked()")
    security = start_preview.index("catch (security: SecurityException)")
    generic = start_preview.index("catch (error: Exception)")

    assert security < generic
    security_block = block_after(start_preview, "catch (security: SecurityException)")
    generic_block = block_after(start_preview, "catch (error: Exception)")
    assert "watchForCameraAvailability" not in security_block
    assert "watchForCameraAvailability(desiredId)" in generic_block


def test_capture_session_failures_recover_only_current_camera():
    create_session = block_after(SOURCE, "private fun createSession()")
    recovery_call = (
        'recoverFromSessionFailure(device, generation, "Could not create camera capture session", error)'
    )
    for exception_type in (
        "IllegalStateException",
        "CameraAccessException",
        "IllegalArgumentException",
    ):
        catch_block = block_after(create_session, f"catch (error: {exception_type})")
        assert recovery_call in catch_block

    configured = block_after(
        create_session,
        "override fun onConfigured(captureSession: CameraCaptureSession)",
    )
    startup = block_after(configured, "runCatching")
    assert "device.createCaptureRequest(template)" in startup
    assert "captureSession.setRepeatingRequest(request, null, handler)" in startup
    failure = block_after(configured, ".onFailure { error ->")
    assert "sessionGeneration.get() == generation && camera === device" in failure
    assert "recoverFromSessionFailure(" in failure
    assert '"Could not start camera repeating request"' in failure

    configure_failed = block_after(
        create_session,
        "override fun onConfigureFailed(captureSession: CameraCaptureSession)",
    )
    guard = "sessionGeneration.get() != generation || camera !== device"
    assert guard in configure_failed
    assert configure_failed.index("captureSession.close()") < configure_failed.index(
        "recoverFromSessionFailure("
    )
    assert '"Capture session configuration failed"' in configure_failed

    recovery = block_after(SOURCE, "private fun recoverFromSessionFailure(")
    assert guard in recovery
    assert recovery.index(guard) < recovery.index("closeCamera()")
    assert recovery.index("closeCamera()") < recovery.index("watchForCameraAvailability(cameraId)")


def test_session_reconfigure_defers_invalid_output_surfaces_before_teardown():
    create_session = block_after(SOURCE, "private fun createSession()")
    assert "previewSurface?.takeIf(Surface::isValid)" in create_session
    assert "captureOutputKinds(preview != null, encoded != null)" in create_session
    assert "outputKinds.isEmpty()" in create_session
    assert "encoded != null && !encoded.isValid" in create_session
    assert create_session.index("outputKinds.isEmpty()") < create_session.index("session?.close()")
    assert create_session.index("encoded != null && !encoded.isValid") < create_session.index("session?.close()")

    rebuild = block_after(SOURCE, "private fun rebuildRepeatingRequest()")
    assert "previewSurface?.takeIf(Surface::isValid)" in rebuild
    assert "if (preview == null && encoded == null) return" in rebuild
    assert "encoded != null && !encoded.isValid" in rebuild
    assert rebuild.index("if (preview == null && encoded == null) return") < rebuild.index(
        "device.createCaptureRequest(template)"
    )


def test_runtime_request_rebuild_recovers_only_broken_current_session():
    rebuild = block_after(SOURCE, "private fun rebuildRepeatingRequest()")
    assert "val generation = sessionGeneration.get()" in rebuild
    failure = block_after(rebuild, ".onFailure { error ->")

    assert "sessionGeneration.get() == generation" in failure
    assert "camera === device" in failure
    assert "session === currentSession" in failure
    assert "error is CameraAccessException || error is IllegalStateException" in failure
    assert "recoverFromSessionFailure(" in failure
    assert '"Could not rebuild camera repeating request"' in failure
    assert 'Log.w(TAG, "Failed to rebuild repeating request", error)' in failure
