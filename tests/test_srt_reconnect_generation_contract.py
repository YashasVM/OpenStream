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

    disconnect = source[disconnect_start:worker_start]
    assert "connectionGeneration_.fetch_add(1, std::memory_order_acq_rel);" in disconnect

    # Only a real send failure may mark the current session unhealthy; stale work
    # returns success from sendNow() and never reaches this branch.
    failure_branch = "if (!sendNow(pending.bytes, pending.generation)) {\n        healthy_ = false;"
    assert failure_branch in worker
