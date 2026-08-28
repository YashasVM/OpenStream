from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_reservation_expires_if_media_never_connects() -> None:
    source = read("android/app/src/main/java/dev/openstream/app/MainActivity.kt")

    reserve_start = source.index("private fun reserveForSource")
    release_start = source.index("private fun releaseForSource", reserve_start)
    reserve = source[reserve_start:release_start]
    listener_start = source.index("private fun startPhoneServerIfAllowed")
    listener_end = source.index("private fun isListenerActive", listener_start)
    listener = source[listener_start:listener_end]
    schedule_start = source.index("private fun scheduleReservationRelease")
    cancel_start = source.index("private fun cancelReservationRelease", schedule_start)
    schedule = source[schedule_start:cancel_start]

    connected_branch = "if (phoneConnected) {\n            cancelReservationRelease()\n        } else {\n            scheduleReservationRelease()\n        }"
    assert connected_branch in reserve

    connected_index = listener.index("phoneConnected = true")
    cancel_index = listener.index("cancelReservationRelease()", connected_index)
    assert connected_index < cancel_index
    assert "scheduleReservationRelease()" in listener

    assert "mainHandler.postDelayed(releaseReservationRunnable!!, RECONNECT_RESERVATION_MS)" in schedule
    assert "if (!phoneConnected && reservedBy == sourceInstanceId)" in schedule
    assert "reservedBy = null" in schedule
    assert "reservedSlotLabel = null" in schedule
