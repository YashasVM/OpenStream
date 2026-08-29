from pathlib import Path


SOURCE = Path("android/app/src/main/cpp/openstream_srt.cpp")


def _block_after(text: str, marker: str) -> str:
    marker_index = text.index(marker)
    brace_start = text.index("{", marker_index)
    depth = 0
    for index in range(brace_start, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[brace_start + 1 : index]
    raise AssertionError(f"Unclosed block after {marker!r}")


def test_queue_limit_allows_one_isolated_large_access_unit():
    source = SOURCE.read_text(encoding="utf-8")
    send = _block_after(source, "bool send(std::vector<uint8_t> bytes)")

    oversized = "const bool oversizedAccessUnit = byteCount > kMaximumSendQueueBytes;"
    isolated = (
        "const bool allowIsolatedOversizedAccessUnit =\n"
        "          oversizedAccessUnit && sendQueue_.empty() && sendQueueBytes_ == 0;"
    )
    overflow = (
        "const bool wouldExceedQueueLimit =\n"
        "          !allowIsolatedOversizedAccessUnit &&\n"
        "          (oversizedAccessUnit || sendQueueBytes_ > kMaximumSendQueueBytes - byteCount);"
    )

    assert oversized in send
    assert isolated in send
    assert overflow in send
    assert send.index(oversized) < send.index(isolated) < send.index(overflow)

    saturation_guard = _block_after(send, "if (!healthy_.load(std::memory_order_acquire)")
    assert "wouldExceedQueueLimit" in send[: send.index(saturation_guard)]
    assert "healthy_ = false;" in saturation_guard
    assert "sendQueue_.clear();" in saturation_guard

    # The backlog cap still protects queued accumulation. Only an access unit
    # arriving to an otherwise empty queue may exceed the byte threshold.
    assert "sendQueue_.empty() && sendQueueBytes_ == 0" in isolated
    assert "sendQueueBytes_ > kMaximumSendQueueBytes - byteCount" in overflow
