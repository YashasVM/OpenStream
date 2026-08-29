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
    start_preview = _block_after(SOURCE, "fun startPreview()")
    opened = _block_after(start_preview, "override fun onOpened(device: CameraDevice)")
    disconnected = _block_after(start_preview, "override fun onDisconnected(device: CameraDevice)")
    errored = _block_after(start_preview, "override fun onError(device: CameraDevice, error: Int)")

    assert "val generation = cameraGeneration.get()" in start_preview
    assert "cameraGeneration.get() != generation || activeCameraId != desiredId" in opened
    assert opened.index("device.close()") < opened.index("camera = device")
    assert "cameraGeneration.get() != generation || camera !== device" in disconnected
    assert "cameraGeneration.get() != generation || camera !== device" in errored


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
