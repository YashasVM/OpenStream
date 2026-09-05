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
    generic_select = source.index("phone = ctx->phone_discovery.select(", exhausted)
    assert expiry < exhausted < generic_select

    reset = source.index("const auto reset_reconnect_episode")
    reset_clear = source.index("reconnect_hold_exhausted = false;", reset)
    attempt_counter_reset = source.index("ctx->frames_output = 0;", reset_clear)
    decoded = source.index("decode_packets(ctx", attempt_counter_reset)
    media_gate = source.index(
        "if (ctx->frames_output >= kReconnectRecoveryVideoFrames)", decoded
    )
    reset_call = source.index("reset_reconnect_episode();", media_gate)
    next_hold = source.index("hold_phone_for_reconnect(reserved_phone);", reset_call)
    assert attempt_counter_reset < decoded < media_gate < reset_call < next_hold


def test_reconnect_episode_requires_sustained_decoded_video_per_reopen() -> None:
    source = SOURCE.read_text(encoding="utf-8")

    assert "constexpr uint64_t kReconnectRecoveryVideoFrames = 30;" in source
    assert "if (ctx->frames_output > 0)" not in source
    assert source.count("ctx->frames_output = 0;") == 1

    opened = source.index("ctx->phone_connected = true;")
    attempt_counter_reset = source.index("ctx->frames_output = 0;", opened)
    decoder_open = source.index("open_video_decoder(", attempt_counter_reset)
    decoded = source.index("decode_packets(ctx", decoder_open)
    media_gate = source.index(
        "if (ctx->frames_output >= kReconnectRecoveryVideoFrames)", decoded
    )
    assert opened < attempt_counter_reset < decoder_open < decoded < media_gate


def test_reservation_success_does_not_refresh_reconnect_deadline() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    selection_start = source.index(
        "while (!ctx->stop_requested.load()) {", source.index('srt_url == "openstream:auto"')
    )
    selection_end = source.index(
        "if (!reservation_acquired || !phone.has_value())", selection_start
    )
    selection_loop = source[selection_start:selection_end]

    assert "reserve_phone(ctx, *phone)" in selection_loop
    assert "hold_phone_for_reconnect(phone)" not in selection_loop


def test_unreserved_phone_never_reaches_active_or_srt_state_on_stop() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    auto_start = source.index('if (srt_url == "openstream:auto")')
    success_flag = source.index("bool reservation_acquired = false;", auto_start)
    reserve_success = source.index("reservation_acquired = true;", success_flag)
    success_gate = source.index(
        "if (!reservation_acquired || !phone.has_value())", reserve_success
    )
    stop_gate = source.index("if (ctx->stop_requested.load())", success_gate)
    stop_release = source.index("queue_release_phone(ctx, *phone);", stop_gate)
    reserved_assignment = source.index("reserved_phone = phone;", stop_release)
    active_assignment = source.index("set_active_phone(ctx, phone);", reserved_assignment)
    srt_assignment = source.index('srt_url = "srt://"', active_assignment)

    assert success_flag < reserve_success < success_gate < stop_gate
    assert stop_gate < stop_release < reserved_assignment < active_assignment < srt_assignment


def test_expired_reconnect_phone_is_deprioritized_but_remains_fallback() -> None:
    source = SOURCE.read_text(encoding="utf-8")

    selector_start = source.index("std::optional<PhoneDevice> select(")
    selector_end = source.index("\n private:", selector_start)
    selector = source[selector_start:selector_end]
    assert 'const std::string &deprioritized_id = ""' in selector

    remember = selector.index("deprioritized = entry.second;")
    skip_old = selector.index("continue;", remember)
    choose_alternate = selector.index("return entry.second;", skip_old)
    fallback_old = selector.index("return deprioritized;", choose_alternate)
    assert remember < skip_old < choose_alternate < fallback_old

    expiry = source.index("Reconnect hold expired; allowing %s to choose another phone")
    remember_expired = source.index("expired_reconnect_phone_id = reconnect_phone_id;", expiry)
    clear_pin = source.index("reconnect_phone_id.clear();", remember_expired)
    exhausted = source.index("reconnect_hold_exhausted = true;", clear_pin)
    deprioritize = source.index("const std::string deprioritized_phone_id", exhausted)
    selection = source.index("phone = ctx->phone_discovery.select(", deprioritize)
    assert expiry < remember_expired < clear_pin < exhausted < deprioritize < selection

    reset = source.index("const auto reset_reconnect_episode")
    reset_end = source.index("};", reset)
    assert "expired_reconnect_phone_id.clear();" in source[reset:reset_end]
