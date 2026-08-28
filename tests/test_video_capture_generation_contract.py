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


def test_video_callbacks_are_generation_bound_across_restart():
    source = VIDEO_ENCODER.read_text(encoding="utf-8")

    start = source[source.index("fun start()") : source.index("fun stop()")]
    stop = source[source.index("fun stop()") : source.index("private fun deliverIfCurrent")]
    delivery = source[
        source.index("private fun deliverIfCurrent") : source.index("private fun chooseEncoder")
    ]

    assert "private val lifecycleLock = Any()" in source
    assert "private val deliveryLock = Any()" in source
    assert "private val callbackLock = Any()" in source
    assert "fun start() = synchronized(lifecycleLock)" in start
    assert "fun stop() = synchronized(lifecycleLock)" in stop

    start_delivery = _block_after(start, "synchronized(deliveryLock)")
    stop_delivery = _block_after(stop, "synchronized(deliveryLock)")
    callback_delivery = _block_after(delivery, "synchronized(deliveryLock)")

    assert "streamGeneration = nextGeneration" in start_delivery
    assert "streamGeneration += 1" in stop_delivery
    assert "if (streamGeneration != generation) return false" in callback_delivery
    assert "onEncodedAccessUnit(accessUnit)" in callback_delivery

    # Output buffers are copied/released while stop() is excluded from releasing
    # the MediaCodec instance, and payload delivery is revalidated afterwards.
    output_callback = source[
        source.index("override fun onOutputBufferAvailable") : source.index("override fun onError")
    ]
    assert "synchronized(callbackLock)" in output_callback
    assert "if (streamGeneration != generation)" in output_callback
    assert "deliverIfCurrent(generation, accessUnit)" in output_callback

    stop_callback = _block_after(stop, "synchronized(callbackLock)")
    assert "encoder.stop()" in stop_callback
    assert "encoder.release()" in stop_callback

    format_callback = source[
        source.index("override fun onOutputFormatChanged") : source.index("}, handler)")
    ]
    assert "if (streamGeneration != generation) return" in format_callback
    assert "deliverIfCurrent(" in format_callback
