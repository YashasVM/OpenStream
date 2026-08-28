from pathlib import Path


SOURCE = Path("android/app/src/main/cpp/openstream_srt.cpp")


def test_queued_media_is_bound_to_one_srt_session():
    source = SOURCE.read_text(encoding="utf-8")

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
