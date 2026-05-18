from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_architecture_documents_practical_v1_transport() -> None:
    architecture = read("docs/architecture.md")
    assert "MediaCodec hardware HEVC/H.264 encode" in architecture
    assert "SRT caller" in architecture
    assert "UDP discovery" in architecture
    assert "PTP" in architecture
    assert "not required for the first prototype" in architecture


def test_android_project_declares_camera_media_codec_srt_discovery_boundaries() -> None:
    app = read("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    discovery = read("android/app/src/main/java/dev/openstream/app/discovery/ObsDiscoveryClient.kt")
    manifest = read("android/app/src/main/AndroidManifest.xml")
    assert "Camera2" in app
    assert "MediaCodec" in app
    assert "SrtStreamClient" in app
    assert "Available OBS devices" in app
    assert "ObsDiscoveryClient" in app
    assert "startPreviewIfAllowed" in app
    assert "startStreaming(encoder.inputSurface())" in app
    assert "OPENSTREAM/1" in discovery
    assert "DISCOVERY_PORT = 51515" in discovery
    assert "DEVICE_TTL_MS = 5_000L" in discovery
    assert "createMulticastLock" in discovery
    assert "CHANGE_WIFI_MULTICAST_STATE" in manifest
    assert "RECORD_AUDIO" not in app


def test_android_connection_target_builds_srt_caller_url_and_pairing_targets() -> None:
    target = read("android/app/src/main/java/dev/openstream/app/stream/ConnectionTarget.kt")
    assert "toSrtCallerUrl" in target
    assert "mode=caller" in target
    assert "DEFAULT_PORT = 9000" in target
    assert "fromDiscoveredDevice" in target
    assert "fromPairingUri" in target
    assert "openstream" in target


def test_obs_plugin_registers_openstream_source_and_discovery() -> None:
    source = read("obs-plugin/src/openstream-source.cpp")
    assert "openstream_source" in source
    assert "obs_register_source" in source
    assert "OpenStream Phone" in source
    assert "listener_enabled" in source
    assert "reserve_listener_port" in source
    assert "discovery_broadcast_addresses" in source
    assert "DiscoveryAdvertiser" in source
    assert "kDiscoveryPort = 51515" in source
    assert "OPENSTREAM/1" in source
    assert "srt_url" in source
    assert "listener_port" in source
    assert "phone_target_hint" in source
    assert "pairing_url" in source


def test_camera_controller_supports_preview_before_streaming() -> None:
    camera = read("android/app/src/main/java/dev/openstream/app/camera/Camera2Controller.kt")
    assert "fun startPreview()" in camera
    assert "fun startStreaming(encodedSurface: Surface)" in camera
    assert "fun stopStreaming()" in camera
    assert "TEMPLATE_PREVIEW" in camera
    assert "TEMPLATE_RECORD" in camera


def test_android_default_build_requires_libsrt_with_ci_escape_hatch() -> None:
    gradle = read("android/app/build.gradle.kts")
    cmake = read("android/app/src/main/cpp/CMakeLists.txt")
    assert "openstream.nonStreamingCiBuild" in gradle
    assert "?: !nonStreamingCiBuild" in gradle
    assert "third_party/srt" in cmake
    assert "lib/${ANDROID_ABI}/libsrt.a" in cmake
    assert "OPENSTREAM_ENABLE_LIBSRT" in cmake
    assert "OPENSTREAM_HAVE_LIBSRT=1" in cmake


def test_receiver_validates_srt_support() -> None:
    receiver = read("tools/openstream_receiver.py")
    assert "ffmpeg_supports_srt" in receiver
    assert "mode=listener" in receiver
    assert "mode=caller" in receiver


def test_protocol_documents_media_and_telemetry_contracts() -> None:
    protocol = read("docs/protocol.md")
    assert "MediaCodec" in protocol
    assert "MPEG-TS" in protocol
    assert "OPENSTREAM/1" in protocol
    assert "openstream://connect" in protocol
    assert "deviceName" in protocol
    assert "latency_ms" in protocol


def test_docs_keep_python_receiver_as_developer_tool_only() -> None:
    setup = read("docs/setup.md")
    assert "Normal use does not require the standalone Python receiver" in setup
    assert "developer/debug path only" in setup
