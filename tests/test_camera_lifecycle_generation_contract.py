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


def test_stale_camera_device_callbacks_cannot_replace_new_device():
    start_preview = _block_after(SOURCE, "private fun startPreviewLocked()")
    opened = _block_after(start_preview, "override fun onOpened(device: CameraDevice)")
    disconnected = _block_after(start_preview, "override fun onDisconnected(device: CameraDevice)")
    errored = _block_after(start_preview, "override fun onError(device: CameraDevice, error: Int)")

    assert "val generation = cameraGeneration.get()" in start_preview
    assert "val expectedLifecycleGeneration = lifecycleGeneration" in start_preview

    for callback in (opened, disconnected, errored):
        lifecycle_critical = _block_after(callback, "synchronized(lifecycleLock)")
        assert "!desiredRunning" in lifecycle_critical
        assert "lifecycleGeneration != expectedLifecycleGeneration" in lifecycle_critical
        assert "cameraGeneration.get() != generation" in lifecycle_critical
        assert "activeCameraId != desiredId" in lifecycle_critical

    opened_critical = _block_after(opened, "synchronized(lifecycleLock)")
    assert opened_critical.index("device.close()") < opened_critical.index("camera = device")
    assert "cancelCameraRecoveryLocked()" in opened_critical


def test_camera_lifecycle_mutators_share_device_callback_lock():
    switch_lens = _block_after(SOURCE, "fun switchLens(lens: CameraLens)")
    switch_critical = _block_after(switch_lens, "synchronized(lifecycleLock)")
    assert "activeCameraId = newId" in switch_critical
    assert "closeCamera()" in switch_critical
    assert switch_critical.index("closeCamera()") < switch_critical.index("startPreview()")

    start_streaming = _block_after(SOURCE, "fun startStreaming(encodedSurface: Surface)")
    start_critical = _block_after(start_streaming, "synchronized(lifecycleLock)")
    assert "streamingSurface = encodedSurface" in start_critical
    assert "startPreview()" in start_critical
    assert "createSession()" in start_critical

    stop_streaming = _block_after(SOURCE, "fun stopStreaming()")
    stop_critical = _block_after(stop_streaming, "synchronized(lifecycleLock)")
    assert "streamingSurface = null" in stop_critical
    assert "createSession()" in stop_critical


def test_session_recovery_cannot_race_lifecycle_mutation():
    recovery = _block_after(SOURCE, "private fun recoverFromSessionFailure(")
    lifecycle_critical = _block_after(recovery, "synchronized(lifecycleLock)")

    assert "!desiredRunning" in lifecycle_critical
    assert "sessionGeneration.get() != generation" in lifecycle_critical
    assert "camera !== device" in lifecycle_critical
    assert "closeCamera()" in lifecycle_critical
    assert "watchForCameraAvailability(cameraId)" in lifecycle_critical
    assert lifecycle_critical.index("closeCamera()") < lifecycle_critical.index(
        "watchForCameraAvailability(cameraId)"
    )


def test_stale_capture_session_callbacks_are_closed_not_published():
    create_session = _block_after(SOURCE, "private fun createSession()")
    configured = _block_after(
        create_session,
        "override fun onConfigured(captureSession: CameraCaptureSession)",
    )
    failed = _block_after(
        create_session,
        "override fun onConfigureFailed(captureSession: CameraCaptureSession)",
    )

    assert "val generation = sessionGeneration.incrementAndGet()" in create_session
    guard = "sessionGeneration.get() != generation || camera !== device"
    assert guard in configured
    assert configured.index("captureSession.close()") < configured.index("session = captureSession")
    assert guard in failed


def test_close_invalidates_device_and_session_callbacks_before_teardown():
    close_camera = _block_after(SOURCE, "private fun closeCamera()")

    camera_invalidate = close_camera.index("cameraGeneration.incrementAndGet()")
    session_invalidate = close_camera.index("sessionGeneration.incrementAndGet()")
    session_close = close_camera.index("session?.close()")
    camera_close = close_camera.index("camera?.close()")

    assert camera_invalidate < session_close
    assert session_invalidate < session_close
    assert session_close < camera_close
