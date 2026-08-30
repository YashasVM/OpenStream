from pathlib import Path


SRT_CLIENT = Path(
    "android/app/src/main/java/dev/openstream/app/stream/SrtStreamClient.kt"
)
MAIN_ACTIVITY = Path("android/app/src/main/java/dev/openstream/app/MainActivity.kt")


def _block_after(source: str, marker: str) -> str:
    start = source.index(marker)
    brace = source.index("{", start)
    depth = 0
    for index in range(brace, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[brace + 1 : index]
    raise AssertionError(f"Unclosed block after {marker!r}")


def test_send_failures_preserve_transport_generation_for_recovery():
    client = SRT_CLIENT.read_text(encoding="utf-8")

    assert "data class SrtSendResult(" in client
    assert "val sessionGeneration: Long" in client
    assert "val recoveryRequired: Boolean = false" in client

    video = client[
        client.index("fun sendVideoAccessUnit") : client.index("fun sendAudioAccessUnit")
    ]
    audio = client[
        client.index("fun sendAudioAccessUnit") : client.index("fun isCurrentSessionGeneration")
    ]
    for send in (video, audio):
        assert "val generation = sessionGeneration.get()" in send
        assert "SrtSendResult(sent, generation, recoveryRequired = !sent)" in send


def test_main_activity_drops_stale_failure_before_destructive_recovery():
    activity = MAIN_ACTIVITY.read_text(encoding="utf-8")

    audio_callback = _block_after(activity, "onEncodedAccessUnit = { accessUnit ->")
    video_start = activity.index("private fun createVideoEncoder")
    video_callback = _block_after(
        activity[video_start:], "onEncodedAccessUnit = { accessUnit ->"
    )
    for callback in (audio_callback, video_callback):
        assert "result.recoveryRequired" in callback
        assert "isCurrentSessionGeneration(result.sessionGeneration)" in callback
        assert "handleMediaTransportFailure(result.sessionGeneration)" in callback

    handler = _block_after(activity, "private fun handleMediaTransportFailure(sessionGeneration: Long)")
    assert "if (!streamClient.isCurrentSessionGeneration(sessionGeneration)) return" in handler
    posted = _block_after(handler, "mainHandler.post")
    stale_guard = posted.index(
        "if (!streamClient.isCurrentSessionGeneration(sessionGeneration)) return@post"
    )
    stop_stream = posted.index("stopStream(updateStatus = false)")
    assert stale_guard < stop_stream
