"""Screen source lifecycle guards; device consent still requires an instrumentation run."""
from pathlib import Path

SOURCE = (Path(__file__).resolve().parents[1] / 'android/app/src/main/java/dev/openstream/app/MainActivity.kt').read_text()


def body(name, next_name):
    return SOURCE.split(name, 1)[1].split(next_name, 1)[0]


def test_background_capture_preserves_pipeline_and_surface_destruction_does_not_disconnect():
    stopped = body('override fun onStop()', 'override fun onDestroy()')
    assert stopped.index('if (screenCaptureActive || screenConsentPending)') < stopped.index('camera.stop()')
    destroyed = body('override fun surfaceDestroyed', 'override fun onStart()')
    assert destroyed.index('if (screenCaptureActive || screenConsentPending) return') < destroyed.index('camera.stop()')


def test_projection_releases_before_codec_and_cannot_reconnect_into_hidden_camera():
    teardown = body('private fun stopActiveEncoding', 'private fun selectForSource')
    assert teardown.index('stopScreenCapture()') < teardown.index('encoder.stop()')
    projection = body('private fun stopScreenCapture()', 'private fun updateAdaptiveBitrate()')
    assert 'phoneServerRunning = false' in projection
    assert 'listenerGeneration += 1' in projection
    assert 'camera.start' not in projection


def test_consent_is_explicit_and_audio_limit_is_visible():
    assert 'startActivityForResult(screenCapture.consentIntent(), 402)' in SOURCE
    assert "playback audio is not captured" in SOURCE
    assert 'requestCode == 402' in SOURCE
    assert 'resultCode == RESULT_OK && data != null && activeTargetName != null' in SOURCE
