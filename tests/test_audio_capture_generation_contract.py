from pathlib import Path


AUDIO_ENCODER = Path(
    "android/app/src/main/java/dev/openstream/app/encoder/MediaCodecAudioEncoder.kt"
)


def test_audio_capture_generation_invalidates_stale_workers_before_delivery():
    source = AUDIO_ENCODER.read_text(encoding="utf-8")

    start = source[source.index("fun start()") : source.index("fun stop()")]
    stop = source[source.index("fun stop()") : source.index("private fun drainEncoder")]
    drain = source[source.index("private fun drainEncoder") : source.index("private fun codecConfigFrom")]

    assert "val generation = captureGeneration + 1" in start
    assert "captureGeneration = generation" in start
    assert "while (captureGeneration == generation)" in start
    assert "if (captureGeneration != generation) break" in start
    assert "drainEncoder(encoder, generation)" in start

    assert "captureGeneration += 1" in stop

    assert "private fun drainEncoder(encoder: MediaCodec, generation: Long)" in drain
    assert "while (captureGeneration == generation)" in drain
    stale_guard = drain.index("if (captureGeneration != generation)")
    first_delivery = drain.index("onEncodedAccessUnit(")
    assert stale_guard < first_delivery
