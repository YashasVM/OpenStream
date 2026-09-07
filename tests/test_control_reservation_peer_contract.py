from pathlib import Path


SERVER = Path(
    "android/app/src/main/java/dev/openstream/app/control/CameraControlServer.kt"
).read_text()


def _function(name: str) -> str:
    start = SERVER.index(f"private fun {name}(")
    brace = SERVER.index("{", start)
    depth = 0
    for index in range(brace, len(SERVER)):
        if SERVER[index] == "{":
            depth += 1
        elif SERVER[index] == "}":
            depth -= 1
            if depth == 0:
                return SERVER[start : index + 1]
    raise AssertionError(f"unterminated function: {name}")


def test_reservation_captures_controller_peer():
    reserve = _function("handleReserve")
    assert "controllerAddress: String" in reserve
    assert "activeControllerAddress = controllerAddress.ifEmpty { null }" in reserve


def test_different_source_cannot_replace_active_reservation():
    reserve = _function("handleReserve")
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
    reserve = _function("handleReserve")
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
    reserve = _function("handleReserve")
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
    reserve = _function("handleReserve")
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
    reserve = _function("handleReserve")
    release = _function("handleRelease")
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
        body = _function(handler)
        guard = "if (!isAuthorizedController(controllerAddress)) return unauthorizedControlResponse()"
        assert guard in body
        assert body.index(guard) < body.index(side_effect)


def test_authorization_requires_live_reservation_and_matching_peer():
    auth = _function("isAuthorizedController")
    assert "reservationProvider() != null" in auth
    assert "controllerAddress.isNotEmpty()" in auth
    assert "controllerAddress == activeControllerAddress" in auth


def test_release_requires_current_controller_peer_before_token_or_side_effects():
    release = _function("handleRelease")
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
    release = _function("handleRelease")
    clear = "activeControllerAddress = null"
    assert clear in release
    assert release.index("activeReservationToken = null") < release.index(clear)
    assert release.index(clear) < release.index("activeReservationSlotLabel = null")
    assert release.index("activeReservationSlotLabel = null") < release.index(
        "activeReservationBitrateMbps = null"
    )


def test_control_server_is_not_exposed_as_a_cross_origin_browser_api():
    handle_client = _function("handleClient")
    send_response = _function("sendResponse")
    assert 'method == "OPTIONS"' not in handle_client
    assert "Access-Control-Allow-Origin" not in send_response
    assert "Access-Control-Allow-Methods" not in send_response
    assert "Access-Control-Allow-Headers" not in send_response


def test_mutating_routes_require_application_json_before_body_or_dispatch():
    handle_client = _function("handleClient")
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
    send_response = _function("sendResponse")
    assert '415 -> "Unsupported Media Type"' in send_response


def test_duplicate_reserve_retry_cannot_reach_android_lease_scheduler():
    activity = Path(
        "android/app/src/main/java/dev/openstream/app/MainActivity.kt"
    ).read_text(encoding="utf-8")

    reserve = _function("handleReserve")
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
