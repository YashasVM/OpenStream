from pathlib import Path

from _helpers import block_after

AUDIO_ENCODER = Path(
    "android/app/src/main/java/dev/openstream/app/encoder/MediaCodecAudioEncoder.kt"
)
SRT_CLIENT = Path(
    "android/app/src/main/java/dev/openstream/app/stream/SrtStreamClient.kt"
)


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

    start_delivery = block_after(start, "synchronized(deliveryLock)")
    stop_delivery = block_after(stop, "synchronized(deliveryLock)")
    callback_delivery = block_after(delivery, "synchronized(deliveryLock)")

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


def test_failed_audio_startup_rolls_back_partial_resources():
    source = AUDIO_ENCODER.read_text(encoding="utf-8")
    start = source[source.index("fun start()") : source.index("fun stop()")]

    assert "fun start() = synchronized(lifecycleLock)" in start
    assert "try {" in start
    assert "codec = encoder" in start
    assert "audioRecord = recorder" in start
    assert "recorder.startRecording()" in start

    catch_body = block_after(start, "catch (error: Throwable)")
    assert "stop()" in catch_body
    assert "throw error" in catch_body
    assert catch_body.index("stop()") < catch_body.index("throw error")


def test_runtime_audio_codec_failure_reaches_reconnect_path():
    encoder_source = AUDIO_ENCODER.read_text(encoding="utf-8")
    client_source = SRT_CLIENT.read_text(encoding="utf-8")

    capture_thread = encoder_source[
        encoder_source.index("captureThread = Thread") : encoder_source.index("fun stop()")
    ]
    failure_handler = block_after(capture_thread, "catch (error: Exception)")

    assert "if (captureGeneration == generation)" in failure_handler
    assert "encoderFailure = true" in failure_handler
    assert "deliverIfCurrent(" in failure_handler

    send_audio = client_source[
        client_source.index("fun sendAudioAccessUnit") : client_source.index("fun isCurrentSessionGeneration")
    ]
    failure_guard = send_audio.index("if (accessUnit.encoderFailure)")
    mark_failure = send_audio.index("markSendFailure(generation)", failure_guard)
    native_send = send_audio.index("SrtNativeBridge.sendAudio")

    assert failure_guard < mark_failure < native_send
    failure_result = send_audio[mark_failure:native_send]
    assert "SrtSendResult(false, generation, recoveryRequired = true)" in failure_result


def test_audio_record_read_error_enters_runtime_failure_handler():
    encoder_source = AUDIO_ENCODER.read_text(encoding="utf-8")
    capture_thread = encoder_source[
        encoder_source.index("captureThread = Thread") : encoder_source.index("fun stop()")
    ]

    negative_read_branch = block_after(capture_thread, "else if (bytesRead < 0)")
    catch_index = capture_thread.index("catch (error: Exception)")
    negative_branch_index = capture_thread.index("else if (bytesRead < 0)")

    assert negative_branch_index < catch_index
    assert "throw IllegalStateException" in negative_read_branch
    assert "AudioRecord read failed" in negative_read_branch
