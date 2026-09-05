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


def test_unreachable_release_teardown_has_bounded_urgent_retry_budget():
    header = Path("obs-plugin/src/async-control-client.hpp").read_text(encoding="utf-8")
    impl = Path("obs-plugin/src/async-control-client.cpp").read_text(encoding="utf-8")

    assert "static constexpr int kUrgentRetryAttempts = 3;" in header
    assert "for (int attempt = 0; attempt < kUrgentRetryAttempts; ++attempt)" in impl
    assert "std::chrono::milliseconds(150)" in impl

    source = SOURCE
    release_start = source.index("void queue_release_phone(")
    release_end = source.index("std::string av_error", release_start)
    release = source[release_start:release_end]
    assert "post_urgent(" in release
    assert "send_control_command(host, port, \"/release\"" in release

    stop_start = source.index("void openstream_stop_worker(")
    stop_end = source.index("bool open_video_decoder", stop_start)
    stop = source[stop_start:stop_end]
    assert "queue_release_phone(ctx, *reserved_phone);" in stop

    destroy_start = source.index("void openstream_destroy(")
    destroy_end = source.index("void openstream_defaults", destroy_start)
    destroy = source[destroy_start:destroy_end]
    assert destroy.index("openstream_stop_worker(ctx);") < destroy.index(
        "ctx->camera_controls->stop();"
    )
