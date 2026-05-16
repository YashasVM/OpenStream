from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_architecture_documents_practical_v1_transport() -> None:
    architecture = read("docs/architecture.md")
    assert "MediaCodec hardware HEVC/H.264 encode" in architecture
    assert "SRT caller" in architecture
    assert "PTP" in architecture
    assert "not required for the first prototype" in architecture


def test_android_project_declares_camera_media_codec_srt_boundaries() -> None:
    app = read("android/app/src/main/java/dev/openstream/app/MainActivity.kt")
    assert "Camera2" in app
    assert "MediaCodec" in app
    assert "SrtStreamClient" in app


def test_obs_plugin_registers_openstream_source() -> None:
    source = read("obs-plugin/src/openstream-source.cpp")
    assert "openstream_source" in source
    assert "obs_register_source" in source
    assert "srt_url" in source


def test_receiver_validates_srt_support() -> None:
    receiver = read("tools/openstream_receiver.py")
    assert "ffmpeg_supports_srt" in receiver
    assert "mode=listener" in receiver
    assert "mode=caller" in receiver


def test_protocol_documents_media_and_telemetry_contracts() -> None:
    protocol = read("docs/protocol.md")
    assert "MediaCodec" in protocol
    assert "MPEG-TS" in protocol
    assert "deviceName" in protocol
    assert "latency_ms" in protocol
