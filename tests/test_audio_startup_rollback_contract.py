from pathlib import Path


AUDIO_ENCODER = Path(
    "android/app/src/main/java/dev/openstream/app/encoder/MediaCodecAudioEncoder.kt"
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


def test_failed_audio_startup_rolls_back_partial_resources():
    source = AUDIO_ENCODER.read_text(encoding="utf-8")
    start = source[source.index("fun start()") : source.index("fun stop()")]

    assert "fun start() = synchronized(lifecycleLock)" in start
    assert "try {" in start
    assert "codec = encoder" in start
    assert "audioRecord = recorder" in start
    assert "recorder.startRecording()" in start

    catch_body = _block_after(start, "catch (error: Throwable)")
    assert "stop()" in catch_body
    assert "throw error" in catch_body
    assert catch_body.index("stop()") < catch_body.index("throw error")
