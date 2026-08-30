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
        "currentControllerAddress != null",
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
    assert "activeControllerAddress != null" in release
    assert peer_guard in release
    assert "return unauthorizedControlResponse()" in release
    assert release.index(peer_guard) < release.index(
        "reservationToken != activeReservationToken"
    )
    assert release.index("return unauthorizedControlResponse()") < release.index(
        "val released = onRelease(sourceInstanceId)"
    )


def test_release_clears_controller_peer_with_reservation_state():
    release = _function("handleRelease")
    clear = "activeControllerAddress = null"
    assert clear in release
    assert release.index("activeReservationToken = null") < release.index(clear)
