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


def test_media_send_cannot_cross_disconnect_or_reconnect_boundary():
    source = SOURCE.read_text(encoding="utf-8")

    video = _block_after(source, "fun sendVideoAccessUnit")
    audio = _block_after(source, "fun sendAudioAccessUnit")
    disconnect = _block_after(source, "fun disconnect()")
    establish = _block_after(source, "private inline fun establishSession")

    # The native send itself must stay inside stateLock. A generation check made
    # before releasing the lock is insufficient because disconnect/reconnect can
    # otherwise replace the native session before the old access unit is sent.
    assert "synchronized(stateLock)" in source[source.index("fun sendVideoAccessUnit") : source.index("fun sendAudioAccessUnit")]
    assert "SrtNativeBridge.sendVideo(" in video
    assert "synchronized(stateLock)" in source[source.index("fun sendAudioAccessUnit") : source.index("fun disconnect()")]
    assert "SrtNativeBridge.sendAudio(" in audio

    # Disconnect invalidates the generation and tears down native transport while
    # holding the same lock used by both media send paths.
    disconnect_locked = _block_after(disconnect, "synchronized(stateLock)")
    assert "sessionGeneration.incrementAndGet()" in disconnect_locked
    assert "connected = false" in disconnect_locked
    assert "SrtNativeBridge.disconnect()" in disconnect_locked

    # Starting a replacement session closes the Kotlin send gate before native
    # connect/listen mutates transport state, so no access unit can enter during
    # the handoff window. The same generation is handed to the native operation
    # so multi-address connection fallback can stop after lifecycle cancellation.
    establish_locked = _block_after(establish, "synchronized(stateLock)")
    assert "connected = false" in establish_locked
    assert establish_locked.index("connected = false") < establish_locked.index("sessionGeneration.incrementAndGet()")
    native_operation_index = establish.index("val didConnect = nativeOperation(generation)")
    assert establish.index("val generation = synchronized(stateLock)") < native_operation_index
