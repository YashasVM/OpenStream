from pathlib import Path


SOURCE = Path("android/app/src/main/java/dev/openstream/app/MainActivity.kt")


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


def test_stale_transport_failure_cannot_clear_replacement_connection_state():
    source = SOURCE.read_text(encoding="utf-8")
    handler = _block_after(source, "private fun handleMediaTransportFailure(sessionGeneration: Long)")

    post_block = _block_after(handler, "mainHandler.post")
    generation_guard = (
        "if (!streamClient.isCurrentSessionGeneration(sessionGeneration)) return@post"
    )

    assert generation_guard in post_block
    assert "phoneConnected = false" in post_block
    assert post_block.index(generation_guard) < post_block.index("phoneConnected = false")

    before_post = handler[: handler.index("mainHandler.post")]
    assert "phoneConnected = false" not in before_post
