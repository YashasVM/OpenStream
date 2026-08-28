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

    assert "if (phoneConnected)" in reserve
    assert "cancelReservationRelease()" in reserve
    assert "scheduleReservationRelease()" in reserve
    assert "phoneConnected = true" in listener
    assert "cancelReservationRelease()" in listener
    assert "scheduleReservationRelease()" in listener
