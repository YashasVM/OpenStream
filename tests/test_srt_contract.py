from pathlib import Path

from _helpers import block_after

SRT_CLIENT = Path(
    "android/app/src/main/java/dev/openstream/app/stream/SrtStreamClient.kt"
)
SRT_NATIVE = Path("android/app/src/main/cpp/openstream_srt.cpp")
OBS_PLUGIN_SOURCE = Path("obs-plugin/src/openstream-source.cpp")
SRT_PROBE = Path("tools/srt_timeout_probe.py")

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "android/app/src/main/java/dev/openstream/app/stream/SrtStreamClient.kt"
NATIVE = ROOT / "android/app/src/main/cpp/openstream_srt.cpp"


def test_srt_address_fallback_uses_fresh_native_connects_and_honors_cancellation():
    source = SRT_CLIENT.read_text(encoding="utf-8")

    connect = block_after(source, "fun connect(")
    fallback = block_after(source, "private fun connectToResolvedAddress(")
    resolver = block_after(source, "private fun resolvedConnectUrls(")

    assert "connectToResolvedAddress(url, codecMime, width, height, fps, generation)" in connect
    assert "InetAddress.getAllByName(host)" in resolver
    assert ".distinct()" in resolver

    candidate_loop = block_after(fallback, "for (candidateUrl in resolvedConnectUrls(url))")
    generation_guard = "if (!isCurrentSessionGeneration(generation)) return false"
    native_connect = "SrtNativeBridge.connect(candidateUrl, codecMime, width, height, fps, generation)"

    assert generation_guard in candidate_loop
    assert native_connect in candidate_loop
    assert candidate_loop.index(generation_guard) < candidate_loop.index(native_connect)

    # Each resolved address gets a fresh native socket. The expected lifecycle
    # generation is also passed into native code so a cancellation between this
    # Kotlin guard and the JNI call cannot publish a stale replacement socket.
    assert f"if ({native_connect})" in candidate_loop
    assert "return true" in block_after(candidate_loop, "if (SrtNativeBridge.connect")


def test_srt_runtime_lifetime_is_process_scoped_and_disconnect_safe():
    source = SRT_NATIVE.read_text(encoding="utf-8")

    # NativeSender owns one libsrt runtime reference for its full lifetime.
    # connect()/listen() must never acquire or release that global reference,
    # so disconnect cannot tear the runtime down while setup is in progress.
    constructor_start = source.index("NativeSender()")
    destructor_start = source.index("~NativeSender()", constructor_start)
    connect_start = source.index("bool connect(")
    constructor = source[constructor_start:destructor_start]
    destructor = source[destructor_start:connect_start]
    connect = source[connect_start : source.index("bool listen(")]
    listen = source[source.index("bool listen(") : source.index("bool sendNow(")]
    disconnect = source[source.index("void disconnect()") : source.index("private:", source.index("void disconnect()"))]

    assert "srt_startup()" in constructor
    assert "srtStarted_ = srt_startup() == 0;" in constructor
    assert "srt_cleanup()" not in constructor
    assert "srt_cleanup();" in destructor
    assert "srt_startup()" not in connect
    assert "srt_startup()" not in listen
    assert "srt_cleanup()" not in connect
    assert "srt_cleanup()" not in listen
    assert "srt_cleanup()" not in disconnect

    # Connection attempts still fail cleanly if process-level startup failed.
    assert connect.count("if (!srtStarted_)") == 1
    assert listen.count("if (!srtStarted_)") == 1

    # Post-startup failures close their sockets/session state via disconnect,
    # without changing the process-scoped libsrt runtime ownership.
    assert "Could not create SRT socket\");\n      disconnect();\n      return false;" in connect
    assert "Could not resolve SRT host\");\n      disconnect();\n      return false;" in connect
    assert "SRT connect failed: %s\", srt_getlasterror_str());\n      disconnect();\n      return false;" in connect
    assert "Could not create SRT listener socket\");\n      disconnect();\n      return false;" in listen
    assert "SRT bind failed: %s\", srt_getlasterror_str());\n      disconnect();\n      return false;" in listen
    assert "SRT listen failed: %s\", srt_getlasterror_str());\n      disconnect();\n      return false;" in listen
    assert "SRT accept failed: %s\", srt_getlasterror_str());\n      disconnect();\n      return false;" in listen


def test_native_connect_and_listen_publish_only_current_generation():
    kotlin = KOTLIN.read_text()
    native = NATIVE.read_text()
    establish = block_after(kotlin, "private inline fun establishSession")
    assert "SrtNativeBridge.beginSession(generation)" in establish
    assert "SrtNativeBridge.connect(candidateUrl, codecMime, width, height, fps, generation)" in kotlin
    assert "SrtNativeBridge.listen(url, codecMime, width, height, fps, generation)" in kotlin

    publish = block_after(native, "bool setSocketForLifecycle")
    assert "std::lock_guard<std::mutex> lock(socketMutex_)" in publish
    assert publish.index("lifecycleGeneration_ != expectedLifecycleGeneration") < publish.index("socket_ = socket")

    listener_publish = block_after(native, "bool setListenerSocketForLifecycle")
    assert "std::lock_guard<std::mutex> lock(socketMutex_)" in listener_publish
    assert listener_publish.index("lifecycleGeneration_ != expectedLifecycleGeneration") < listener_publish.index("listener_socket_ = socket")

    connect = block_after(native, "bool connect(const std::string &url, uint64_t expectedLifecycleGeneration)")
    assert connect.index("setSocketForLifecycle(socket, expectedLifecycleGeneration)") < connect.index("srt_connect(")

    listen = block_after(native, "bool listen(const std::string &url, uint64_t expectedLifecycleGeneration)")
    assert "setListenerSocketForLifecycle(listenerSocket, expectedLifecycleGeneration)" in listen
    assert "setSocketForLifecycle(acceptedSocket, expectedLifecycleGeneration)" in listen


def test_disconnect_invalidates_generation_before_native_teardown():
    native = NATIVE.read_text()
    body = block_after(native, "Java_dev_openstream_app_stream_SrtNativeBridge_disconnect")
    invalidate = "g_state.sender.advanceLifecycleGeneration(generation)"
    teardown = "g_state.sender.disconnect()"
    assert body.index(invalidate) < body.index(teardown)


def test_native_media_and_stale_teardown_are_generation_guarded():
    native = NATIVE.read_text()
    video = block_after(native, "Java_dev_openstream_app_stream_SrtNativeBridge_sendVideo")
    audio = block_after(native, "Java_dev_openstream_app_stream_SrtNativeBridge_sendAudio")
    disconnect = block_after(native, "Java_dev_openstream_app_stream_SrtNativeBridge_disconnect")

    generation_guard = (
        "static_cast<uint64_t>(session_generation) != "
        "g_state.mediaSessionGeneration"
    )
    assert generation_guard in video
    assert video.index(generation_guard) < video.index("g_state.muxer->muxAccessUnit")
    assert generation_guard in audio
    assert audio.index(generation_guard) < audio.index("g_state.muxer->muxAudioAccessUnit")

    # A cancelled older connection must not roll back the native lifecycle or
    # tear down a newer generation installed by concurrent disconnect().
    assert "generation < g_state.mediaSessionGeneration" in disconnect
    assert disconnect.index("generation < g_state.mediaSessionGeneration") < disconnect.index(
        "g_state.sender.advanceLifecycleGeneration(generation)"
    )


def test_queue_limit_allows_only_bounded_isolated_large_access_unit():
    source = SRT_NATIVE.read_text(encoding="utf-8")
    send = block_after(source, "bool send(std::vector<uint8_t> bytes)")

    assert "static constexpr size_t kMaximumAccessUnitBytes = 2 * 1024 * 1024;" in source
    assert "const bool accessUnitTooLarge = byteCount > kMaximumAccessUnitBytes;" in send
    assert "const bool oversizedAccessUnit = byteCount > kMaximumSendQueueBytes;" in send

    isolated = (
        "const bool allowIsolatedOversizedAccessUnit =\n"
        "          !accessUnitTooLarge && oversizedAccessUnit && sendQueue_.empty() && sendQueueBytes_ == 0;"
    )
    overflow = (
        "const bool wouldExceedQueueLimit =\n"
        "          accessUnitTooLarge ||\n"
        "          (!allowIsolatedOversizedAccessUnit &&\n"
        "           (oversizedAccessUnit || sendQueueBytes_ > kMaximumSendQueueBytes - byteCount));"
    )

    assert isolated in send
    assert overflow in send
    assert send.index("accessUnitTooLarge") < send.index("allowIsolatedOversizedAccessUnit")
    assert send.index("allowIsolatedOversizedAccessUnit") < send.index("wouldExceedQueueLimit")

    saturation_guard = block_after(send, "if (!healthy_.load(std::memory_order_acquire)")
    assert "healthy_ = false;" in saturation_guard
    assert "connectionGeneration_.fetch_add(1, std::memory_order_acq_rel);" in saturation_guard
    assert "sendQueue_.clear();" in saturation_guard
    assert "SRT access unit exceeds safety limit" in saturation_guard

    # Large keyframes may exceed the backlog threshold when isolated, but no
    # single muxed access unit may bypass the finite safety cap.
    assert "!accessUnitTooLarge" in isolated
    assert "sendQueue_.empty() && sendQueueBytes_ == 0" in isolated
    assert "accessUnitTooLarge ||" in overflow


def test_backlog_budget_keeps_in_flight_access_unit_counted_until_send_finishes():
    source = SRT_NATIVE.read_text(encoding="utf-8")
    worker = block_after(source, "void runSendWorker()")

    pop_index = worker.index("sendQueue_.pop_front();")
    send_index = worker.index("sendNow(pending.bytes, pending.generation)")
    assert "sendQueueBytes_ -= pending.bytes.size();" not in worker[pop_index:send_index]

    completion = block_after(worker, "if (pending.generation == connectionGeneration_.load")
    assert "sendQueueBytes_ -= pending.bytes.size();" in completion
    assert worker.index("sendNow(pending.bytes, pending.generation)") < worker.index(
        "if (pending.generation == connectionGeneration_.load"
    )


def test_queued_media_is_bound_to_one_srt_session():
    source = SRT_NATIVE.read_text(encoding="utf-8")

    send_start = source.index("bool send(std::vector<uint8_t> bytes)")
    disconnect_start = source.index("void disconnect()", send_start)
    send = source[send_start:disconnect_start]

    worker_start = source.index("void runSendWorker()")
    stop_worker_start = source.index("void stopSendWorker()", worker_start)
    worker = source[worker_start:stop_worker_start]

    failure_start = source.index("void markGenerationFailed(uint64_t generation)")
    failure_end = source.index("void runSendWorker()", failure_start)
    failure = source[failure_start:failure_end]

    send_now_start = source.index("bool sendNow(")
    send_start_again = source.index("bool send(std::vector<uint8_t> bytes)", send_now_start)
    send_now = source[send_now_start:send_start_again]

    assert "const uint64_t generation = connectionGeneration_.load(std::memory_order_acquire);" in send
    assert "sendQueue_.push_back(PendingSend{generation, std::move(bytes)});" in send
    assert "pending = std::move(sendQueue_.front());" in worker
    assert "sendNow(pending.bytes, pending.generation)" in worker

    stale_guard = "if (generation != connectionGeneration_.load(std::memory_order_acquire)) {\n      return true;\n    }"
    assert stale_guard in send_now
    assert send_now.index(stale_guard) < send_now.index("const SRTSOCKET socket = currentSocket();")

    # disconnect() invalidates the generation before it waits on ioMutex_. A
    # send already inside sendNow() must therefore re-check between SRT chunks;
    # otherwise a large keyframe can hold reconnect teardown behind many send
    # timeouts even though the session has already been cancelled.
    chunk_loop = block_after(send_now, "while (offset < bytes.size())")
    chunk_generation_guard = "if (generation != connectionGeneration_.load(std::memory_order_acquire))"
    assert chunk_generation_guard in chunk_loop
    chunk_guard_body = block_after(chunk_loop, chunk_generation_guard)
    assert "return true;" in chunk_guard_body
    assert chunk_loop.index(chunk_generation_guard) < chunk_loop.index("srt_sendmsg(")

    disconnect = source[disconnect_start:failure_start]
    assert "connectionGeneration_.fetch_add(1, std::memory_order_acq_rel);" in disconnect

    # A send failure must be applied only while its generation still owns the
    # socket lifecycle. Holding socketMutex_ across the generation check and
    # queue clear prevents a replacement setSocket() from being poisoned.
    generation_guard = "if (generation != connectionGeneration_.load(std::memory_order_acquire)) {\n      return;\n    }"
    assert "std::lock_guard<std::mutex> socketLock(socketMutex_);" in failure
    assert generation_guard in failure
    assert failure.index("socketLock(socketMutex_)") < failure.index(generation_guard)
    assert failure.index(generation_guard) < failure.index("healthy_ = false;")
    assert failure.index("healthy_ = false;") < failure.index("sendQueue_.clear();")
    assert "markGenerationFailed(pending.generation);" in worker


def test_media_send_cannot_cross_disconnect_or_reconnect_boundary():
    source = SRT_CLIENT.read_text(encoding="utf-8")

    video = block_after(source, "fun sendVideoAccessUnit")
    audio = block_after(source, "fun sendAudioAccessUnit")
    disconnect = block_after(source, "fun disconnect()")
    establish = block_after(source, "private inline fun establishSession")

    # Sends may run outside stateLock so disconnect is not held behind JNI, but
    # the snapshotted generation must cross the JNI boundary. Native then rejects
    # an old access unit even if a replacement session wins the race.
    assert "SrtNativeBridge.sendVideo(\n            accessUnit.data,\n            accessUnit.presentationTimeUs,\n            accessUnit.flags,\n            generation," in video
    assert "SrtNativeBridge.sendAudio(\n            accessUnit.data,\n            accessUnit.presentationTimeUs,\n            accessUnit.flags,\n            generation," in audio
    bridge = source[source.index("private object SrtNativeBridge") :]
    # Safe wrappers (fun sendVideo/sendAudio) must forward generation to private
    # native methods; direct external funs are also accepted for legacy builds.
    assert ("nativeSendVideo(" in bridge or "external fun sendVideo(" in bridge)
    assert ("nativeSendAudio(" in bridge or "external fun sendAudio(" in bridge)
    assert "sessionGeneration: Long," in bridge
    # Load failure must never crash: library load is guarded and surfaced.
    assert "loadError" in bridge and "isAvailable" in bridge
    failure = block_after(source, "private fun markNativeSendFailure")
    assert "if (sessionGeneration.get() == generation)" in failure
    assert failure.index("if (sessionGeneration.get() == generation)") < failure.index(
        "sendFailures.incrementAndGet()"
    )

    # Disconnect invalidates the Kotlin generation and passes that new generation
    # to native teardown while holding the same lock used by both media send paths.
    disconnect_locked = block_after(disconnect, "synchronized(stateLock)")
    assert "val generation = sessionGeneration.incrementAndGet()" in disconnect_locked
    assert "connected = false" in disconnect_locked
    assert "SrtNativeBridge.disconnect(generation)" in disconnect_locked

    # Starting a replacement session closes the Kotlin send gate and advances the
    # native generation before connect/listen can publish any socket.
    establish_locked = block_after(establish, "synchronized(stateLock)")
    assert "connected = false" in establish_locked
    assert establish_locked.index("connected = false") < establish_locked.index("sessionGeneration.incrementAndGet()")
    assert "SrtNativeBridge.beginSession(generation)" in establish_locked
    native_operation_index = establish.index("val didConnect = nativeOperation(generation)")
    assert establish.index("val generation = synchronized(stateLock)") < native_operation_index


def test_probe_cannot_block_on_unread_ffmpeg_stderr():
    source = SRT_PROBE.read_text(encoding="utf-8")

    assert "stderr=subprocess.PIPE" not in source
    assert source.count("stderr=subprocess.DEVNULL") >= 2

    receiver_start = source.index("receiver = subprocess.Popen(")
    receiver_end = source.index("progress_thread =", receiver_start)
    receiver = source[receiver_start:receiver_end]

    assert "stdout=subprocess.PIPE" in receiver
    assert "-progress" in receiver
    assert "pipe:1" in receiver


def test_obs_srt_input_has_bounded_io_and_connect_timeouts():
    source = OBS_PLUGIN_SOURCE.read_text(encoding="utf-8")

    assert "constexpr int64_t kSrtIoTimeoutUs = 4'500'000;" in source
    assert "constexpr int64_t kSrtConnectTimeoutMs = 2'000;" in source

    options_start = source.index("AVDictionary *options = nullptr;")
    open_start = source.index("avformat_open_input(", options_start)
    options = source[options_start:open_start]

    assert 'av_dict_set_int(&options, "timeout", kSrtIoTimeoutUs, 0);' in options
    assert 'av_dict_set_int(&options, "connect_timeout", kSrtConnectTimeoutMs, 0);' in options
