from pathlib import Path


SOURCE = Path(__file__).resolve().parents[1] / "src" / "openstream-source.cpp"


def test_worker_exit_releases_active_phone_before_clearing_it() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    worker_start = source.index("void openstream_worker(")
    cleanup_start = source.index(
        "ctx->listener_running = false;",
        worker_start,
    )
    cleanup_end = source.index(
        'blog(LOG_INFO, "[OpenStream] Listener worker exited")',
        cleanup_start,
    )
    cleanup = source[cleanup_start:cleanup_end]

    assert "final_phone_to_release" in cleanup
    assert "queue_release_phone(ctx, *final_phone_to_release);" in cleanup
    assert cleanup.index("queue_release_phone(ctx, *final_phone_to_release);") < cleanup.index(
        "set_active_phone(ctx, std::nullopt);"
    )

