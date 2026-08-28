from pathlib import Path


AUDIO_ENCODER = Path(
    "android/app/src/main/java/dev/openstream/app/encoder/MediaCodecAudioEncoder.kt"
)


def test_audio_capture_generation_serializes_invalidation_with_delivery():
    source = AUDIO_ENCODER.read_text(encoding="utf-8")

    start = source[source.index("fun start()") : source.index("fun stop()")]
    stop = source[source.index("fun stop()") : source.index("private fun drainEncoder")]
    drain = source[source.index("private fun drainEncoder") : source.index("private fun deliverIfCurrent")]
    delivery = source[
        source.index("private fun deliverIfCurrent") : source.index("private fun codecConfigFrom")
    ]

    assert "private val deliveryLock = Any()" in source
    assert "val generation = synchronized(deliveryLock)" in start
    assert "captureGeneration = nextGeneration" in start
    assert "while (captureGeneration == generation)" in start
    assert "drainEncoder(encoder, generation)" in start

    stop_lock = stop.index("synchronized(deliveryLock)")
    invalidation = stop.index("captureGeneration += 1")
    assert stop_lock < invalidation

    assert "private fun deliverIfCurrent(generation: Long, accessUnit: EncodedAccessUnit): Boolean" in delivery
    delivery_lock = delivery.index("synchronized(deliveryLock)")
    stale_guard = delivery.index("if (captureGeneration != generation) return false")
    callback = delivery.index("onEncodedAccessUnit(accessUnit)")
    assert delivery_lock < stale_guard < callback

    # Every encoded callback path is forced through the serialized helper, so stop()
    # cannot invalidate the old generation and return while a stale callback is
    # between validation and delivery to MainActivity.
    assert "onEncodedAccessUnit(" not in drain
    assert drain.count("deliverIfCurrent(generation, accessUnit)") == 2
