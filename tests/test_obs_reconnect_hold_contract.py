from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "obs-plugin" / "src" / "openstream-source.cpp"


def test_auto_slot_reconnect_hold_is_single_shot_until_media_recovers() -> None:
    source = SOURCE.read_text(encoding="utf-8")

    assert "bool reconnect_hold_exhausted = false;" in source
    assert "!phone.has_value() || reconnect_hold_exhausted" in source
    assert "if (!reconnect_phone_id.empty())" in source
    assert source.count(
        "reconnect_deadline = std::chrono::steady_clock::now() + kReconnectReservationWindow;"
    ) == 1

    expiry = source.index("Reconnect hold expired; allowing %s to choose another phone")
    exhausted = source.index("reconnect_hold_exhausted = true;", expiry)
    generic_select = source.index(
        "phone = ctx->phone_discovery.select(effective_phone_id, ctx->instance_id);",
        exhausted,
    )
    assert expiry < exhausted < generic_select

    reset = source.index("const auto reset_reconnect_episode")
    reset_clear = source.index("reconnect_hold_exhausted = false;", reset)
    decoded = source.index("decode_packets(ctx", reset_clear)
    media_gate = source.index("if (ctx->frames_output > 0)", decoded)
    reset_call = source.index("reset_reconnect_episode();", media_gate)
    next_hold = source.index("hold_phone_for_reconnect(reserved_phone);", reset_call)
    assert decoded < media_gate < reset_call < next_hold


def test_reservation_success_does_not_refresh_reconnect_deadline() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    selection_start = source.index("while (!ctx->stop_requested.load()) {", source.index("srt_url == \"openstream:auto\""))
    selection_end = source.index("if (!phone.has_value())", selection_start)
    selection_loop = source[selection_start:selection_end]

    assert "reserve_phone(ctx, *phone)" in selection_loop
    assert "hold_phone_for_reconnect(phone)" not in selection_loop
