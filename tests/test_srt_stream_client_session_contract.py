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

    # Sends may run outside stateLock so disconnect is not held behind JNI, but
    # the snapshotted generation must cross the JNI boundary. Native then rejects
    # an old access unit even if a replacement session wins the race.
    assert "SrtNativeBridge.sendVideo(\n            accessUnit.data,\n            accessUnit.presentationTimeUs,\n            accessUnit.flags,\n            generation," in video
    assert "SrtNativeBridge.sendAudio(\n            accessUnit.data,\n            accessUnit.presentationTimeUs,\n            accessUnit.flags,\n            generation," in audio
    bridge = source[source.index("private object SrtNativeBridge") :]
    assert "external fun sendVideo(\n        data: ByteArray,\n        presentationTimeUs: Long,\n        flags: Int,\n        sessionGeneration: Long," in bridge
    assert "external fun sendAudio(\n        data: ByteArray,\n        presentationTimeUs: Long,\n        flags: Int,\n        sessionGeneration: Long," in bridge
    failure = _block_after(source, "private fun markNativeSendFailure")
    assert "if (sessionGeneration.get() == generation)" in failure
    assert failure.index("if (sessionGeneration.get() == generation)") < failure.index(
        "sendFailures.incrementAndGet()"
    )

    # Disconnect invalidates the Kotlin generation and passes that new generation
    # to native teardown while holding the same lock used by both media send paths.
    disconnect_locked = _block_after(disconnect, "synchronized(stateLock)")
    assert "val generation = sessionGeneration.incrementAndGet()" in disconnect_locked
    assert "connected = false" in disconnect_locked
    assert "SrtNativeBridge.disconnect(generation)" in disconnect_locked

    # Starting a replacement session closes the Kotlin send gate and advances the
    # native generation before connect/listen can publish any socket.
    establish_locked = _block_after(establish, "synchronized(stateLock)")
    assert "connected = false" in establish_locked
    assert establish_locked.index("connected = false") < establish_locked.index("sessionGeneration.incrementAndGet()")
    assert "SrtNativeBridge.beginSession(generation)" in establish_locked
    native_operation_index = establish.index("val didConnect = nativeOperation(generation)")
    assert establish.index("val generation = synchronized(stateLock)") < native_operation_index
