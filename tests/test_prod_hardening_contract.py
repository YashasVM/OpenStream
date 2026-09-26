"""Prod-hardening contracts: #40 crash fixes merged into #41 reservation model.

Unified design (prod-ready):
- Android never crashes on missing native lib / null CameraManager; teardown
  and lens restart stay off the UI thread (AGENTS.md 6).
- ReservationState stays authoritative: pending advertised-not-busy, confirmed
  busy, 45s lease split pending-rollback vs confirmed-release.
- OBS discovery registry is bounded (64, evict-eldest with warning).
- Reserved names its SRT target so TCP-OK vs SRT-blocked is diagnosable.
- Dock keeps full peer-bound controls (8 buttons via bounded send);
  properties stay minimal (naming/testing/disconnect/zoom/identify, no
  torch/lens duplication).
- Docs use canonical ports 51615/239.255.43.99/9100/9101.
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_encoder_has_no_build_breakers():
    src = read("android/app/src/main/java/dev/openstream/app/encoder/MediaCodecVideoEncoder.kt")
    assert "resolvedSelection" not in src
    assert "format.codecConfigBytes()" in src
    assert "EncoderSelection" not in src
    assert "mimeTypes" not in src


def test_advertiser_imports_formatter():
    src = read("android/app/src/main/java/dev/openstream/app/discovery/PhoneDiscoveryAdvertiser.kt")
    assert "import android.text.format.Formatter" in src
    assert "Formatter.formatIpAddress" in src


def test_native_load_is_guarded():
    src = read("android/app/src/main/java/dev/openstream/app/stream/SrtStreamClient.kt")
    assert "loadError" in src
    assert "isAvailable" in src
    assert "SrtNativeBridge.isAvailable" in src


def test_teardown_never_blocks_ui_thread():
    app = read("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    assert "shinActivityStop" in app or "shinStopServer" in app
    assert "Thread(" in app
    assert "Looper.getMainLooper" in app


def test_camera_manager_is_null_safe():
    cam = read("android/app/src/main/java/dev/openstream/app/camera/Camera2Controller.kt")
    assert "applicationContext" in cam
    assert "cameraManager ?:" in cam
    assert "runCatching { selectCameraId" in cam


def test_control_bind_errors_surfaced():
    server = read("android/app/src/main/java/dev/openstream/app/control/CameraControlServer.kt")
    assert "onError" in server
    assert "clientLock" in server
    app = read("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    assert "onError" in app


def test_reservation_model_preserved():
    app = read("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    assert "ReservationState" in app
    assert "advertisedReservationId" in app
    assert "isBusy" in app or "isPhoneBusy" in app


def test_obs_discovery_registry_is_bounded():
    src = read("obs-plugin/src/openstream-source.cpp")
    assert "kMaxDevices" in src
    assert "Discovery cap" in src
    assert "erase(eldest)" in src


def test_reserved_state_names_srt_target():
    src = read("obs-plugin/src/openstream-source.cpp")
    assert "Reserved — waiting for SRT media to " in src
    assert "control OK; waiting for media" in src


def test_properties_stay_minimal_without_duplication():
    src = read("obs-plugin/src/openstream-source.cpp")
    assert '"Test connection"' in src
    assert '"Disconnect / release phone"' in src
    assert '"Zoom"' in src
    assert '"Identify Phone"' in src
    assert '"Torch On"' not in src
    assert '"Torch Off"' not in src
    assert '"Rear Camera"' not in src
    assert '"Front Camera"' not in src


def test_dock_keeps_full_peer_bound_controls():
    dock = read("obs-plugin/src/openstream-dock.cpp")
    for required in ('send("/zoom"', 'send("/lens"', 'send("/torch"', 'send("/identify"'):
        assert required in dock
    assert dock.count("new QPushButton") == 8
