"""Prod-ready contracts: crash-free Android, connectable plugin, minimal UI.

Behavioural guards for the IRL reliability pass:
- Android build-breakers stay fixed (encoder typo, dead code, Formatter).
- Native SRT load never crashes; teardown never blocks UI thread.
- OBS discovery registry is bounded; Reserved state names its SRT target.
- Dock + properties stay minimal: Zoom, naming, testing, disconnect only.
"""

from pathlib import Path

from _helpers import block_after

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_encoder_has_no_build_breakers():
    src = read("android/app/src/main/java/dev/openstream/app/encoder/MediaCodecVideoEncoder.kt")
    assert "resolvedSelection" not in src
    assert "format.codecConfigBytes()" in src
    # Dead unreachable block after first return must stay deleted.
    assert "EncoderSelection" not in src
    assert "mimeTypes" not in src


def test_advertiser_imports_formatter():
    src = read("android/app/src/main/java/dev/openstream/app/discovery/PhoneDiscoveryAdvertiser.kt")
    assert "import android.text.format.Formatter" in src
    assert "Formatter.formatIpAddress" in src


def test_native_load_is_guarded():
    src = read("android/app/src/main/java/dev/openstream/app/stream/SrtStreamClient.kt")
    bridge = src[src.index("private object SrtNativeBridge"):]
    assert "loadError" in bridge
    assert "isAvailable" in bridge
    assert "System.loadLibrary" in bridge
    # establishSession must fail fast with a clear message, not UnsatisfiedLinkError.
    assert "SrtNativeBridge.isAvailable" in src


def test_teardown_never_blocks_ui_thread():
    app = read("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    stop = block_after(app, "private fun stopPhoneServer(")
    assert "Looper.getMainLooper" in stop
    assert 'Thread(blockingWork' in stop or "Thread(" in stop
    on_stop = block_after(app, "override fun onStop()")
    assert "shinActivityStop" in on_stop
    # Lens restart probes MediaCodecList off-UI.
    select = block_after(app, "private fun selectLens(lens: CameraLens)")
    assert "shinLensRestart" in select


def test_camera_manager_is_null_safe_and_non_crashing():
    cam = read("android/app/src/main/java/dev/openstream/app/camera/Camera2Controller.kt")
    assert "applicationContext" in cam
    assert "cameraManager ?: run" in cam or "cameraManager ?:" in cam
    assert "runCatching { selectCameraId" in cam


def test_obs_discovery_registry_is_bounded():
    src = read("obs-plugin/src/openstream-source.cpp")
    assert "kMaxDevices" in src
    assert "Discovery cap" in src
    assert "core->devices_.erase(eldest)" in src


def test_reserved_state_names_srt_target():
    src = read("obs-plugin/src/openstream-source.cpp")
    assert "Reserved — waiting for SRT media to " in src
    assert "control OK; waiting for media" in src


def test_dock_is_minimal():
    dock = read("obs-plugin/src/openstream-dock.cpp")
    assert "Test connection" in dock
    assert "Disconnect / release phone" in dock
    assert "Identify" in dock
    assert "Set zoom" in dock or "zoom_" in dock
    # Lens/torch duplication removed from dock; phone owns them.
    assert '"Rear"' not in dock
    assert '"Front"' not in dock
    assert '"Torch on"' not in dock
    assert '"Torch off"' not in dock


def test_properties_keep_only_zoom_naming_testing_disconnect():
    src = read("obs-plugin/src/openstream-source.cpp")
    # Kept: naming + testing + disconnect + zoom.
    assert '"Camera name"' in src
    assert '"Connection label"' in src
    assert '"Test connection"' in src
    assert '"Disconnect / release phone"' in src
    assert '"Zoom"' in src
    assert '"Identify Phone"' in src
    # Removed duplication.
    assert '"Torch On"' not in src
    assert '"Torch Off"' not in src
    assert '"Rear Camera"' not in src
    assert '"Front Camera"' not in src


def test_docs_use_canonical_ports():
    arch = read("docs/architecture.md")
    assert "51615" in arch
    assert "239.255.43.99" in arch
    assert ":9100" in arch
    assert ":9101" in arch
    assert "51515" not in arch
