from pathlib import Path


AUDIO_ENCODER = Path(
    "android/app/src/main/java/dev/openstream/app/encoder/MediaCodecAudioEncoder.kt"
)
SRT_CLIENT = Path(
    "android/app/src/main/java/dev/openstream/app/stream/SrtStreamClient.kt"
)


def _block_after(source: str, marker: str) -> str:
    start = source.index(marker)
    brace = source.index("{", start)
    depth = 0
    for index in range(brace, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[brace + 1 : index]
    raise AssertionError(f"Unclosed block after {marker!r}")


def test_runtime_audio_codec_failure_reaches_reconnect_path():
    encoder_source = AUDIO_ENCODER.read_text(encoding="utf-8")
    client_source = SRT_CLIENT.read_text(encoding="utf-8")

    capture_thread = encoder_source[
        encoder_source.index("captureThread = Thread") : encoder_source.index("fun stop()")
    ]
    failure_handler = _block_after(capture_thread, "catch (error: Exception)")

    assert "if (captureGeneration == generation)" in failure_handler
    assert "encoderFailure = true" in failure_handler
    assert "deliverIfCurrent(" in failure_handler

    send_audio = client_source[
        client_source.index("fun sendAudioAccessUnit") : client_source.index("fun disconnect()")
    ]
    failure_guard = send_audio.index("if (accessUnit.encoderFailure)")
    mark_failure = send_audio.index("markSendFailure(generation)", failure_guard)
    native_send = send_audio.index("SrtNativeBridge.sendAudio")

    assert failure_guard < mark_failure < native_send
    assert "return false" in send_audio[mark_failure:native_send]
