from pathlib import Path


SOURCE = Path(
    "android/app/src/main/java/dev/openstream/app/encoder/MediaCodecVideoEncoder.kt"
).read_text(encoding="utf-8")


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
    raise AssertionError(f"unterminated block after {marker!r}")


def test_optional_video_tuning_has_core_profile_fallback():
    configure = _block_after(SOURCE, "private fun createConfiguredEncoder")
    assert "for (applyOptionalTuning in listOf(true, false))" in configure
    assert "createVideoFormat(applyOptionalTuning)" in configure
    assert "runCatching { encoder.release() }" in configure
    assert "if (!applyOptionalTuning)" in configure
    assert "throw error" in configure

    video_format = _block_after(SOURCE, "private fun createVideoFormat")
    for optional_key in (
        "MediaFormat.KEY_PRIORITY",
        "MediaFormat.KEY_OPERATING_RATE",
        "MediaFormat.KEY_LATENCY",
        "MediaFormat.KEY_MAX_B_FRAMES",
    ):
        key_position = video_format.index(optional_key)
        guard_position = video_format.rfind("applyOptionalTuning", 0, key_position)
        assert guard_position >= 0, f"{optional_key} must stay behind optional tuning"

    for required_key in (
        "MediaFormat.KEY_COLOR_FORMAT",
        "MediaFormat.KEY_BIT_RATE",
        "MediaFormat.KEY_FRAME_RATE",
        "MediaFormat.KEY_I_FRAME_INTERVAL",
        "MediaFormat.KEY_BITRATE_MODE",
    ):
        assert required_key in video_format
