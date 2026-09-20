from pathlib import Path

from _helpers import ROOT, function_body, read_text as read

SERVER = Path(
    "android/app/src/main/java/dev/openstream/app/control/CameraControlServer.kt"
).read_text()

MAIN_ACTIVITY = Path("android/app/src/main/java/dev/openstream/app/MainActivity.kt")


def test_reservation_expires_if_media_never_connects() -> None:
    source = read("android/app/src/main/java/dev/openstream/app/MainActivity.kt")

    reserve_start = source.index("private fun reserveForSource")
    release_start = source.index("private fun releaseForSource", reserve_start)
    reserve = source[reserve_start:release_start]
    select_start = source.index("private fun selectForSource")
    select_end = source.index("private fun reserveForSource", select_start)
    select = source[select_start:select_end]
    listener_start = source.index("private fun startPhoneServerIfAllowed")
    listener_end = source.index("private fun isListenerActive", listener_start)
    listener = source[listener_start:listener_end]
    schedule_start = source.index("private fun scheduleReservationRelease")
    cancel_start = source.index("private fun cancelReservationRelease", schedule_start)
    schedule = source[schedule_start:cancel_start]
    pending_start = source.index("private fun schedulePendingRelease")
    pending_end = source.index("private fun scheduleReservationRelease", pending_start)
    pending = source[pending_start:pending_end]

    connected_branch = "if (phoneConnected) {\n            cancelReservationRelease()\n        } else {\n            scheduleReservationRelease()\n        }"
    assert connected_branch in reserve
    assert "schedulePendingRelease(sourceInstanceId)" in select
    assert "reservationState.beginSelection" in select
    assert "reservationState.confirm" in reserve

    connected_index = listener.index("phoneConnected = true")
    cancel_index = listener.index("cancelReservationRelease()", connected_index)
    assert connected_index < cancel_index
    assert "scheduleReservationRelease()" in listener

    assert "mainHandler.postDelayed(releaseReservationRunnable!!, RECONNECT_RESERVATION_MS)" in schedule
    assert "if (!phoneConnected &&" in schedule
    assert "reservationState.confirmedSourceInstanceId == sourceInstanceId" in schedule
    assert "reservationGeneration == generation" in schedule
    assert "reservationState.release(sourceInstanceId)" in schedule
    assert "mainHandler.postDelayed(releaseReservationRunnable!!, RECONNECT_RESERVATION_MS)" in pending
    assert "reservationState.rollbackPending(sourceInstanceId)" in pending


def test_reservation_renewal_invalidates_an_already_started_expiry() -> None:
    source = read("android/app/src/main/java/dev/openstream/app/MainActivity.kt")

    reserve_start = source.index("private fun reserveForSource")
    release_start = source.index("private fun releaseForSource", reserve_start)
    reserve = source[reserve_start:release_start]
    schedule_start = source.index("private fun scheduleReservationRelease")
    cancel_start = source.index("private fun cancelReservationRelease", schedule_start)
    schedule = source[schedule_start:cancel_start]

    generation_increment = reserve.index("reservationGeneration += 1")
    reservation_write = reserve.index("reservationState.confirm(")
    assert generation_increment > 0
    assert reservation_write > 0
    assert reservation_write < generation_increment

    capture = schedule.index("val generation = reservationGeneration")
    cancel = schedule.index("cancelReservationRelease()")
    callback = schedule.index("releaseReservationRunnable = Runnable")
    generation_guard = schedule.index("reservationGeneration == generation", callback)
    clear = schedule.index("reservationState.release(sourceInstanceId)", callback)
    assert capture < cancel < callback < generation_guard < clear


def test_reservation_release_is_bound_to_generation_token() -> None:
    source = read("obs-plugin/src/openstream-source.cpp")
    control = read("android/app/src/main/java/dev/openstream/app/control/CameraControlServer.kt")

    assert "std::string reservation_token" in source
    assert "reservationToken" in source
    assert "phone.reservation_token" in source
    assert "const std::string reservation_token = phone.reservation_token" in source
    assert 'json.optString("reservationToken")' in control
    assert "activeReservationToken" in control
    assert "reservationToken != activeReservationToken" in control
    assert '"stale":true' in control


def test_reservation_captures_controller_peer():
    reserve = function_body(SERVER, "handleReserve")
    assert "controllerAddress: String" in reserve
    assert "activeControllerAddress = controllerAddress.ifEmpty { null }" in reserve


def test_different_source_cannot_replace_active_reservation():
    reserve = function_body(SERVER, "handleReserve")
    guard = "currentReservation != null && currentReservation != sourceInstanceId"
    assert guard in reserve
    assert "return busyReservationResponse(currentReservation)" in reserve
    assert reserve.index(guard) < reserve.index(
        "val accepted = onReserve(sourceInstanceId, slotLabel, bitrateMbps)"
    )
    assert reserve.index("return busyReservationResponse(currentReservation)") < reserve.index(
        "activeControllerAddress = controllerAddress.ifEmpty { null }"
    )


def test_same_owner_cannot_move_reservation_to_a_different_peer():
    reserve = function_body(SERVER, "handleReserve")
    guard_terms = [
        "val currentReservation = reservationProvider()",
        "val currentControllerAddress = activeControllerAddress",
        "currentReservation == sourceInstanceId",
        "currentControllerAddress == null",
        "controllerAddress != currentControllerAddress",
        "return unauthorizedControlResponse()",
    ]
    for term in guard_terms:
        assert term in reserve
    assert reserve.index("currentReservation == sourceInstanceId") < reserve.index(
        "val accepted = onReserve(sourceInstanceId, slotLabel, bitrateMbps)"
    )
    assert reserve.index("return unauthorizedControlResponse()") < reserve.index(
        "activeControllerAddress = controllerAddress.ifEmpty { null }"
    )


def test_duplicate_reserve_retry_does_not_refresh_android_reconnect_lease():
    reserve = function_body(SERVER, "handleReserve")
    same_config = "val sameReservationConfig = currentReservation == sourceInstanceId"
    assert same_config in reserve
    assert "activeReservationSlotLabel == slotLabel" in reserve
    assert "activeReservationBitrateMbps == bitrateMbps" in reserve
    duplicate_guard = "if (sameReservationConfig)"
    on_reserve = "val accepted = onReserve(sourceInstanceId, slotLabel, bitrateMbps)"
    assert duplicate_guard in reserve
    assert reserve.index(duplicate_guard) < reserve.index(on_reserve)

    duplicate_block = reserve[
        reserve.index(duplicate_guard) : reserve.index(on_reserve)
    ]
    assert "activeReservationToken = reservationToken" in duplicate_block
    assert 'put("ok", true)' in duplicate_block
    assert "onReserve(" not in duplicate_block


def test_reservation_config_change_still_reaches_reservation_owner():
    reserve = function_body(SERVER, "handleReserve")
    same_config = reserve.index("val sameReservationConfig")
    duplicate_guard = reserve.index("if (sameReservationConfig)", same_config)
    on_reserve = reserve.index(
        "val accepted = onReserve(sourceInstanceId, slotLabel, bitrateMbps)",
        duplicate_guard,
    )
    cache_slot = reserve.index("activeReservationSlotLabel = slotLabel", on_reserve)
    cache_bitrate = reserve.index("activeReservationBitrateMbps = bitrateMbps", cache_slot)
    assert same_config < duplicate_guard < on_reserve < cache_slot < cache_bitrate


def test_unbound_active_reservation_fails_closed_for_renew_and_release():
    reserve = function_body(SERVER, "handleReserve")
    release = function_body(SERVER, "handleRelease")
    assert "currentControllerAddress == null" in reserve
    assert reserve.index("currentControllerAddress == null") < reserve.index(
        "val accepted = onReserve(sourceInstanceId, slotLabel, bitrateMbps)"
    )
    assert "activeControllerAddress == null" in release
    assert release.index("activeControllerAddress == null") < release.index(
        "val released = onRelease(sourceInstanceId)"
    )


def test_mutating_controls_require_reservation_peer_before_side_effects():
    side_effects = {
        "handleZoom": "cameraProvider().setZoom(value)",
        "handleTorch": "onToggleTorch(enabled)",
        "handleLens": "onSwitchLens(target)",
        "handleIdentify": "onIdentify(label, subtitle)",
    }
    for handler, side_effect in side_effects.items():
        body = function_body(SERVER, handler)
        guard = "if (!isAuthorizedController(controllerAddress)) return unauthorizedControlResponse()"
        assert guard in body
        assert body.index(guard) < body.index(side_effect)


def test_authorization_requires_live_reservation_and_matching_peer():
    auth = function_body(SERVER, "isAuthorizedController")
    assert "reservationProvider() != null" in auth
    assert "controllerAddress.isNotEmpty()" in auth
    assert "controllerAddress == activeControllerAddress" in auth


def test_release_requires_current_controller_peer_before_token_or_side_effects():
    release = function_body(SERVER, "handleRelease")
    peer_guard = "controllerAddress != activeControllerAddress"
    assert "controllerAddress: String" in release
    assert "activeControllerAddress == null" in release
    assert peer_guard in release
    assert "return unauthorizedControlResponse()" in release
    assert release.index(peer_guard) < release.index(
        "reservationToken != activeReservationToken"
    )
    assert release.index("return unauthorizedControlResponse()") < release.index(
        "val released = onRelease(sourceInstanceId)"
    )


def test_release_clears_controller_peer_and_reservation_config_cache():
    release = function_body(SERVER, "handleRelease")
    clear = "activeControllerAddress = null"
    assert clear in release
    assert release.index("activeReservationToken = null") < release.index(clear)
    assert release.index(clear) < release.index("activeReservationSlotLabel = null")
    assert release.index("activeReservationSlotLabel = null") < release.index(
        "activeReservationBitrateMbps = null"
    )


def test_control_server_is_not_exposed_as_a_cross_origin_browser_api():
    handle_client = function_body(SERVER, "handleClient")
    send_response = function_body(SERVER, "sendResponse")
    assert 'method == "OPTIONS"' not in handle_client
    assert "Access-Control-Allow-Origin" not in send_response
    assert "Access-Control-Allow-Methods" not in send_response
    assert "Access-Control-Allow-Headers" not in send_response


def test_mutating_routes_require_application_json_before_body_or_dispatch():
    handle_client = function_body(SERVER, "handleClient")
    assert "var contentType: String? = null" in handle_client
    assert 'line.startsWith("Content-Type:", ignoreCase = true)' in handle_client
    assert "contentType = line.substringAfter(\":\").trim()" in handle_client
    for path in ("/zoom", "/torch", "/lens", "/reserve", "/release", "/identify"):
        assert f'"{path}"' in handle_client
    json_guard = 'if (requiresJson && !mediaType.equals("application/json", ignoreCase = true))'
    assert json_guard in handle_client
    assert 'sendResponse(writer, 415, """{\"error\":\"application/json required\"}""")' in handle_client
    assert handle_client.index(json_guard) < handle_client.index("// Read body if present")
    assert handle_client.index(json_guard) < handle_client.index('method == "POST" && path == "/zoom"')


def test_unsupported_media_type_response_is_explicit():
    send_response = function_body(SERVER, "sendResponse")
    assert '415 -> "Unsupported Media Type"' in send_response


def test_duplicate_reserve_retry_cannot_reach_android_lease_scheduler():
    activity = Path(
        "android/app/src/main/java/dev/openstream/app/MainActivity.kt"
    ).read_text(encoding="utf-8")

    reserve = function_body(SERVER, "handleReserve")
    duplicate_start = reserve.index("if (sameReservationConfig)")
    on_reserve = reserve.index(
        "val accepted = onReserve(sourceInstanceId, slotLabel, bitrateMbps)"
    )
    duplicate_block = reserve[duplicate_start:on_reserve]
    assert "onReserve(" not in duplicate_block

    assert "onReserve = { sourceInstanceId, slotLabel, bitrateMbps ->" in activity
    assert "reserveForSource(sourceInstanceId, slotLabel, bitrateMbps)" in activity

    reserve_for_source = activity.index("private fun reserveForSource(")
    schedule = activity.index("scheduleReservationRelease()", reserve_for_source)
    generation = activity.index("reservationGeneration += 1", reserve_for_source)
    assert reserve_for_source < generation < schedule


def test_obs_slot_render_cache_renders_initial_empty_state_and_tracks_bitrate():
    source = MAIN_ACTIVITY.read_text(encoding="utf-8")

    assert "private var lastObsSlotRenderKeys: List<String>? = null" in source
    assert (
        '"${device.sourceInstanceId}|${device.displayLabel}|${device.busy}|'
        '${device.bitrateMbps}|${advertisedReservationId == device.sourceInstanceId}|$phoneConnected"'
        in source
    )
