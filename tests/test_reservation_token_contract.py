from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_reservation_release_is_bound_to_generation_token() -> None:
    source = read("obs-plugin/src/openstream-source.cpp")
    control = read("android/app/src/main/java/dev/openstream/app/control/CameraControlServer.kt")

    assert "std::string reservation_token" in source
    assert '"reservationToken\\\":\\\"" << json_escape(phone.reservation_token)' in source
    assert "const std::string reservation_token = phone.reservation_token" in source
    assert 'json.optString("reservationToken")' in control
    assert "reservationToken != activeReservationToken" in control
    assert '"stale":true' in control
