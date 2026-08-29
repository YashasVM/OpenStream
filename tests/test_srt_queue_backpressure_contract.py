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


def test_queue_limit_allows_only_bounded_isolated_large_access_unit():
    source = SOURCE.read_text(encoding="utf-8")
    send = _block_after(source, "bool send(std::vector<uint8_t> bytes)")

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

    saturation_guard = _block_after(send, "if (!healthy_.load(std::memory_order_acquire)")
    assert "healthy_ = false;" in saturation_guard
    assert "sendQueue_.clear();" in saturation_guard
    assert "SRT access unit exceeds safety limit" in saturation_guard

    # Large keyframes may exceed the backlog threshold when isolated, but no
    # single muxed access unit may bypass the finite safety cap.
    assert "!accessUnitTooLarge" in isolated
    assert "sendQueue_.empty() && sendQueueBytes_ == 0" in isolated
    assert "accessUnitTooLarge ||" in overflow
