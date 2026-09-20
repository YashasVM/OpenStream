from pathlib import Path

from _helpers import block_after

VIDEO_ENCODER = Path(
    "android/app/src/main/java/dev/openstream/app/encoder/MediaCodecVideoEncoder.kt"
)
VIDEO_ENCODER_SOURCE = VIDEO_ENCODER.read_text(encoding="utf-8")
SRT_CLIENT = Path(
    "android/app/src/main/java/dev/openstream/app/stream/SrtStreamClient.kt"
)
MAIN_ACTIVITY = Path("android/app/src/main/java/dev/openstream/app/MainActivity.kt")


def test_video_callbacks_are_generation_bound_across_restart():
    source = VIDEO_ENCODER.read_text(encoding="utf-8")

    start = source[source.index("fun start()") : source.index("fun stop()")]
    stop = source[source.index("fun stop()") : source.index("private fun stopCallbackThread")]
    delivery = source[
        source.index("private fun deliverIfCurrent") : source.index("private fun chooseEncoder")
    ]

    assert "private val lifecycleLock = Any()" in source
    assert "private val deliveryLock = Any()" in source
    assert "private val callbackLock = Any()" in source
    assert "fun start() = synchronized(lifecycleLock)" in start
    assert "fun stop() = synchronized(lifecycleLock)" in stop

    start_delivery = block_after(start, "synchronized(deliveryLock)")
    stop_delivery = block_after(stop, "synchronized(deliveryLock)")
    callback_delivery = block_after(delivery, "synchronized(deliveryLock)")

    assert "streamGeneration = nextGeneration" in start_delivery
    assert "streamGeneration += 1" in stop_delivery
    assert "if (streamGeneration != generation) return false" in callback_delivery
    assert "onEncodedAccessUnit(accessUnit)" in callback_delivery

    # Output buffers are copied/released while stop() is excluded from releasing
    # the MediaCodec instance, and payload delivery is revalidated afterwards.
    output_callback = source[
        source.index("override fun onOutputBufferAvailable") : source.index("override fun onError")
    ]
    callback_critical = block_after(output_callback, "synchronized(callbackLock)")
    assert "if (streamGeneration != generation)" in callback_critical
    assert "deliverIfCurrent(generation, accessUnit)" in output_callback

    stop_callback = block_after(stop, "synchronized(callbackLock)")
    assert "encoder.stop()" in stop_callback
    assert "encoder.release()" in stop_callback

    format_callback = source[
        source.index("override fun onOutputFormatChanged") : source.index("}, handler)")
    ]
    assert "if (streamGeneration != generation) return" in format_callback
    assert "deliverIfCurrent(" in format_callback


def test_video_encoder_callback_thread_is_reaped_on_stop_and_failed_start():
    source = VIDEO_ENCODER.read_text(encoding="utf-8")
    start = source[source.index("fun start()") : source.index("fun stop()")]
    stop = source[source.index("fun stop()") : source.index("private fun stopCallbackThread")]
    cleanup = source[
        source.index("private fun stopCallbackThread") : source.index("private fun deliverIfCurrent")
    ]

    assert "private var callbackThread: HandlerThread? = null" in source
    thread_setup = start[start.index('val thread = HandlerThread("shinEncoder")') :]
    assert 'HandlerThread("shinEncoder").apply { start() }' in thread_setup
    assert "callbackThread = thread" in thread_setup
    assert "val handler = Handler(thread.looper)" in thread_setup

    # Failed MediaCodec callback/start setup must not strand the just-created
    # HandlerThread, and normal stop must reap it even if codec is already null.
    failed_start = thread_setup[thread_setup.index("} catch (error: Throwable) {") :]
    assert "stopCallbackThread()" in failed_start
    assert failed_start.index("stopCallbackThread()") < failed_start.index("throw error")
    assert "val encoder = codec" in stop
    encoder_cleanup = block_after(stop, "if (encoder != null)")
    assert "stopCallbackThread()" not in encoder_cleanup
    assert stop.index("stopCallbackThread()") > stop.index("if (encoder != null)")

    assert "callbackThread = null" in cleanup
    assert "thread.quitSafely()" in cleanup
    assert "thread.join(CALLBACK_THREAD_JOIN_TIMEOUT_MS)" in cleanup
    assert "if (thread.isAlive)" in cleanup
    assert "thread.quit()" in cleanup


def test_video_encoder_error_forces_generation_bound_session_recovery():
    encoder = VIDEO_ENCODER.read_text(encoding="utf-8")
    client = SRT_CLIENT.read_text(encoding="utf-8")

    error_callback = encoder[
        encoder.index("override fun onError") : encoder.index("override fun onOutputFormatChanged")
    ]
    assert "if (streamGeneration != generation) return" in error_callback
    assert "encoderFailure = true" in error_callback
    assert "deliverIfCurrent(" in error_callback

    send_video = client[
        client.index("fun sendVideoAccessUnit") : client.index("fun sendAudioAccessUnit")
    ]
    failure_guard = send_video.index("if (accessUnit.encoderFailure)")
    native_send = send_video.index("SrtNativeBridge.sendVideo")
    assert failure_guard < native_send
    failure_result = send_video[failure_guard:native_send]
    assert "markSendFailure(generation)" in failure_result
    assert "SrtSendResult(false, generation, recoveryRequired = true)" in failure_result


def test_optional_video_tuning_has_core_profile_fallback():
    configure = block_after(VIDEO_ENCODER_SOURCE, "private fun createConfiguredEncoder")
    assert "for (applyOptionalTuning in listOf(true, false))" in configure
    assert "createVideoFormat(applyOptionalTuning)" in configure
    assert "runCatching { encoder.release() }" in configure
    assert "if (!applyOptionalTuning)" in configure
    assert "throw error" in configure

    video_format = block_after(VIDEO_ENCODER_SOURCE, "private fun createVideoFormat")
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


def test_failed_video_callback_setup_rolls_back_partial_resources():
    source = VIDEO_ENCODER.read_text(encoding="utf-8")
    start = source[source.index("fun start()") : source.index("fun stop()")]

    callback_setup = start[start.index("val generation") :]
    try_body = block_after(callback_setup, "try")
    assert "encoder.setCallback(" in try_body
    assert "encoder.start()" in try_body

    catch_body = block_after(callback_setup, "catch (error: Throwable)")
    assert "streamGeneration += 1" in catch_body
    assert "codec = null" in catch_body
    assert "surface = null" in catch_body
    assert "encoder.release()" in catch_body
    assert "throw error" in catch_body


def test_stale_transport_failure_cannot_clear_replacement_connection_state():
    source = MAIN_ACTIVITY.read_text(encoding="utf-8")
    handler = block_after(source, "private fun handleMediaTransportFailure(sessionGeneration: Long)")

    post_block = block_after(handler, "mainHandler.post")
    generation_guard = (
        "if (!streamClient.isCurrentSessionGeneration(sessionGeneration)) return@post"
    )

    assert generation_guard in post_block
    assert "phoneConnected = false" in post_block
    assert post_block.index(generation_guard) < post_block.index("phoneConnected = false")

    before_post = handler[: handler.index("mainHandler.post")]
    assert "phoneConnected = false" not in before_post


def test_send_failures_preserve_transport_generation_for_recovery():
    client = SRT_CLIENT.read_text(encoding="utf-8")

    assert "data class SrtSendResult(" in client
    assert "val sessionGeneration: Long" in client
    assert "val recoveryRequired: Boolean = false" in client

    video = client[
        client.index("fun sendVideoAccessUnit") : client.index("fun sendAudioAccessUnit")
    ]
    audio = client[
        client.index("fun sendAudioAccessUnit") : client.index("fun isCurrentSessionGeneration")
    ]
    for send in (video, audio):
        assert "val generation: Long" in send
        assert "val wasConnected: Boolean" in send
        snapshot = block_after(send, "synchronized(stateLock)")
        assert "generation = sessionGeneration.get()" in snapshot
        assert "wasConnected = connected" in snapshot
        assert "SrtNativeBridge.send" not in snapshot
        assert "SrtSendResult(sent, generation, recoveryRequired = !sent)" in send


def test_video_encoder_selection_is_hardware_avc_only_with_explicit_failure():
    source = VIDEO_ENCODER.read_text(encoding="utf-8")
    choose = source[source.index("private fun chooseEncoder") :]

    # AVC-only target: no HEVC preference branch, explicit hardware AVC failure.
    assert "MIMETYPE_VIDEO_AVC" in choose
    assert "isHardwareAccelerated" in choose
    assert "isSoftwareOnly" in choose
    assert "COLOR_FormatSurface" in choose
    assert "BITRATE_MODE_CBR" in choose
    assert "areSizeAndRateSupported" in choose
    assert "No hardware surface encoder can satisfy" in choose
    assert "needs a hardware AVC encoder" in choose
    # Never enable a software codec silently: failure throws, it does not fall back.
    assert "throw IllegalStateException" in choose
    # Log tag is unified so hardware-skip diagnostics are greppable in one place.
    assert '"shinEncoder"' in choose
    assert '"OpenStreamEncoder"' not in choose


def test_main_activity_drops_stale_failure_before_destructive_recovery():
    activity = MAIN_ACTIVITY.read_text(encoding="utf-8")

    audio_callback = block_after(activity, "onEncodedAccessUnit = { accessUnit ->")
    video_start = activity.index("private fun createVideoEncoder")
    video_callback = block_after(
        activity[video_start:], "onEncodedAccessUnit = { accessUnit ->"
    )
    for callback in (audio_callback, video_callback):
        assert "result.recoveryRequired" in callback
        assert "isCurrentSessionGeneration(result.sessionGeneration)" in callback
        assert "handleMediaTransportFailure(result.sessionGeneration)" in callback

    handler = block_after(activity, "private fun handleMediaTransportFailure(sessionGeneration: Long)")
    assert "if (!streamClient.isCurrentSessionGeneration(sessionGeneration)) return" in handler
    posted = block_after(handler, "mainHandler.post")
    stale_guard = posted.index(
        "if (!streamClient.isCurrentSessionGeneration(sessionGeneration)) return@post"
    )
    stop_stream = posted.index("stopStream(updateStatus = false)")
    assert stale_guard < stop_stream
