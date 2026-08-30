from pathlib import Path


VIDEO_ENCODER = Path(
    "android/app/src/main/java/dev/openstream/app/encoder/MediaCodecVideoEncoder.kt"
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


def test_failed_video_callback_setup_rolls_back_partial_resources():
    source = VIDEO_ENCODER.read_text(encoding="utf-8")
    start = source[source.index("fun start()") : source.index("fun stop()")]

    callback_setup = start[start.index("val generation") :]
    try_body = _block_after(callback_setup, "try")
    assert "encoder.setCallback(" in try_body
    assert "encoder.start()" in try_body

    catch_body = _block_after(callback_setup, "catch (error: Throwable)")
    assert "streamGeneration += 1" in catch_body
    assert "codec = null" in catch_body
    assert "surface = null" in catch_body
    assert "encoder.release()" in catch_body
    assert "throw error" in catch_body
