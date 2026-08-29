from pathlib import Path


AUDIO_ENCODER = Path(
    "android/app/src/main/java/dev/openstream/app/encoder/MediaCodecAudioEncoder.kt"
)


def test_audio_encoder_validates_full_format_before_selection():
    source = AUDIO_ENCODER.read_text(encoding="utf-8")
    selector_start = source.index("private fun createAudioCodec")
    selector_end = source.index("@SuppressLint", selector_start)
    selector = source[selector_start:selector_end]

    assert "createAudioCodec(mime, format)" in source
    assert "info.getCapabilitiesForType(mime)" in selector
    assert "capabilities.isFormatSupported(format)" in selector
    assert selector.index("capabilities.isFormatSupported(format)") < selector.index("candidates.firstOrNull()")
    assert "it.isHardwareAccelerated && !it.isSoftwareOnly" in selector
    assert "MediaCodec.createByCodecName(selected.name)" in selector
    assert "MediaCodec.createEncoderByType" not in selector
