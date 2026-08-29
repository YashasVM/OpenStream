from pathlib import Path


SOURCE = Path("android/app/src/main/java/dev/openstream/app/stream/SrtStreamClient.kt")


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


def test_srt_address_fallback_uses_fresh_native_connects_and_honors_cancellation():
    source = SOURCE.read_text(encoding="utf-8")

    connect = _block_after(source, "fun connect(")
    fallback = _block_after(source, "private fun connectToResolvedAddress(")
    resolver = _block_after(source, "private fun resolvedConnectUrls(")

    assert "connectToResolvedAddress(url, codecMime, width, height, fps, generation)" in connect
    assert "InetAddress.getAllByName(host)" in resolver
    assert ".distinct()" in resolver

    candidate_loop = _block_after(fallback, "for (candidateUrl in resolvedConnectUrls(url))")
    generation_guard = "if (!isCurrentSessionGeneration(generation)) return false"
    native_connect = "SrtNativeBridge.connect(candidateUrl, codecMime, width, height, fps)"

    assert generation_guard in candidate_loop
    assert native_connect in candidate_loop
    assert candidate_loop.index(generation_guard) < candidate_loop.index(native_connect)

    # NativeSender.connect() starts by disconnecting and creates a new SRT
    # socket, so one JNI call per resolved address prevents reuse of a failed
    # socket while the generation guard prevents fallback after lifecycle stop.
    assert "if (SrtNativeBridge.connect(candidateUrl, codecMime, width, height, fps))" in candidate_loop
    assert "return true" in _block_after(candidate_loop, "if (SrtNativeBridge.connect")
