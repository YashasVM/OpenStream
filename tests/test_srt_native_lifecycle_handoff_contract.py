from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "android/app/src/main/java/dev/openstream/app/stream/SrtStreamClient.kt"
NATIVE = ROOT / "android/app/src/main/cpp/openstream_srt.cpp"


def _block_after(source: str, marker: str) -> str:
    start = source.index(marker)
    brace = source.index("{", start)
    depth = 0
    for i in range(brace, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[brace + 1:i]
    raise AssertionError(f"unclosed block after {marker!r}")


def test_native_connect_and_listen_publish_only_current_generation():
    kotlin = KOTLIN.read_text()
    native = NATIVE.read_text()
    establish = _block_after(kotlin, "private inline fun establishSession")
    assert "SrtNativeBridge.beginSession(generation)" in establish
    assert "SrtNativeBridge.connect(candidateUrl, codecMime, width, height, fps, generation)" in kotlin
    assert "SrtNativeBridge.listen(url, codecMime, width, height, fps, generation)" in kotlin

    publish = _block_after(native, "bool setSocketForLifecycle")
    assert "std::lock_guard<std::mutex> lock(socketMutex_)" in publish
    assert publish.index("lifecycleGeneration_ != expectedLifecycleGeneration") < publish.index("socket_ = socket")

    listener_publish = _block_after(native, "bool setListenerSocketForLifecycle")
    assert "std::lock_guard<std::mutex> lock(socketMutex_)" in listener_publish
    assert listener_publish.index("lifecycleGeneration_ != expectedLifecycleGeneration") < listener_publish.index("listener_socket_ = socket")

    connect = _block_after(native, "bool connect(const std::string &url, uint64_t expectedLifecycleGeneration)")
    assert connect.index("setSocketForLifecycle(socket, expectedLifecycleGeneration)") < connect.index("srt_connect(")

    listen = _block_after(native, "bool listen(const std::string &url, uint64_t expectedLifecycleGeneration)")
    assert "setListenerSocketForLifecycle(listenerSocket, expectedLifecycleGeneration)" in listen
    assert "setSocketForLifecycle(acceptedSocket, expectedLifecycleGeneration)" in listen


def test_disconnect_invalidates_generation_before_native_teardown():
    native = NATIVE.read_text()
    body = _block_after(native, "Java_dev_openstream_app_stream_SrtNativeBridge_disconnect")
    invalidate = "g_state.sender.advanceLifecycleGeneration(static_cast<uint64_t>(session_generation))"
    teardown = "g_state.sender.disconnect()"
    assert body.index(invalidate) < body.index(teardown)
