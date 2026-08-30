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


def test_audio_capture_generation_serializes_invalidation_with_delivery():
    source = AUDIO_ENCODER.read_text(encoding="utf-8")

    start = source[source.index("fun start()") : source.index("fun stop()")]
    stop = source[source.index("fun stop()") : source.index("private fun drainEncoder")]
    drain = source[source.index("private fun drainEncoder") : source.index("private fun deliverIfCurrent")]
    delivery = source[
        source.index("private fun deliverIfCurrent") : source.index("private fun codecConfigFrom")
    ]

    assert "private val lifecycleLock = Any()" in source
    assert "private val deliveryLock = Any()" in source
    assert "fun start() = synchronized(lifecycleLock)" in start
    assert "fun stop() = synchronized(lifecycleLock)" in stop

    start_delivery = _block_after(start, "synchronized(deliveryLock)")
    stop_delivery = _block_after(stop, "synchronized(deliveryLock)")
    callback_delivery = _block_after(delivery, "synchronized(deliveryLock)")

    assert "captureGeneration = nextGeneration" in start_delivery
    assert "captureGeneration += 1" in stop_delivery
    assert "if (captureGeneration != generation) return false" in callback_delivery
    assert "onEncodedAccessUnit(accessUnit)" in callback_delivery

    assert "while (captureGeneration == generation)" in start
    assert "drainEncoder(encoder, generation)" in start

    # Every encoded callback path is forced through the serialized helper, so stop()
    # cannot invalidate the old generation and return while a stale callback is
    # between validation and delivery to MainActivity.
    assert "onEncodedAccessUnit(" not in drain
    assert drain.count("deliverIfCurrent(generation, accessUnit)") == 2
