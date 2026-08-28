from pathlib import Path


SOURCE = Path("android/app/src/main/cpp/openstream_srt.cpp")


def test_queued_media_is_bound_to_one_srt_session():
    source = SOURCE.read_text(encoding="utf-8")

    assert "struct PendingSend" in source
    assert "uint64_t generation;" in source
    assert "std::deque<PendingSend> sendQueue_;" in source
    assert "PendingSend{generation, std::move(bytes)}" in source

    # Disconnect invalidates already-popped work before a replacement socket
    # can become the current session.
    assert "connectionGeneration_.fetch_add(1, std::memory_order_acq_rel);" in source
    assert "generation != connectionGeneration_.load(std::memory_order_acquire)" in source

    # Stale work is consumed without marking the newly connected session bad.
    stale_guard = "if (generation != connectionGeneration_.load(std::memory_order_acquire)) {\n      return true;\n    }"
    assert stale_guard in source
