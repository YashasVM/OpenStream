from pathlib import Path

SOURCE = Path("obs-plugin/src/openstream-source.cpp").read_text(encoding="utf-8")


def _function_body(name: str, next_name: str) -> str:
    start = SOURCE.index(name)
    end = SOURCE.index(next_name, start)
    return SOURCE[start:end]


def test_camera_control_connect_has_explicit_deadline():
    control = _function_body("bool send_control_command", "void set_active_phone")

    assert "kControlConnectTimeout" in SOURCE
    assert "connect_socket_with_timeout" in control
    assert "std::chrono::milliseconds(1000)" in SOURCE
    assert "if (connect(sock" not in control
    assert "if (::connect(sock" not in control


def test_bounded_connect_uses_nonblocking_completion_check():
    helper = _function_body("bool connect_socket_with_timeout", "std::string json_escape")

    assert "ioctlsocket" in helper
    assert "O_NONBLOCK" in helper
    assert "select(" in helper
    assert "SO_ERROR" in helper
    assert "restore_blocking" in helper
