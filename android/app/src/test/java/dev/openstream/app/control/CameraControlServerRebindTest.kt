package dev.openstream.app.control

import dev.openstream.app.camera.CameraLens
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControlServerRebindTest {
    @Test
    fun rebindsControllerPeerAcrossUsbToWifiSwitchOnlyWithDestinationBoundProof() {
        var currentReservation: String? = null
        val server = CameraControlServer(
            cameraProvider = { error("camera not needed for reserve test") },
            lensListProvider = { listOf(CameraLens.Back) },
            currentLensProvider = { CameraLens.Back },
            onSwitchLens = {},
            onToggleTorch = {},
            reservationProvider = { currentReservation },
            onReserve = { sourceId, _, _ ->
                if (currentReservation == null || currentReservation == sourceId) {
                    currentReservation = sourceId
                    true
                } else {
                    false
                }
            },
            onRelease = { sourceId ->
                if (currentReservation == sourceId) {
                    currentReservation = null
                    true
                } else {
                    false
                }
            },
            onIdentify = { _, _ -> },
        )

        val initialToken = "0123456789abcdef0123456789abcdef"
        val reserveBody =
            """{"sourceInstanceId":"obs-1","reservationToken":"$initialToken","slotLabel":"CAM A","bitrateMbps":12}"""

        val (initialStatus, _) = server.handleReserveRequest(
            reserveBody,
            controllerAddress = "192.168.42.10",
            localAddress = "192.168.42.129",
        )
        assertEquals(200, initialStatus)
        assertTrue(server.isPeerAuthorized("192.168.42.10"))

        // Plain token replay without a destination-bound proof must be rejected on a new IP.
        val (plainTokenStatus, _) = server.handleReserveRequest(
            reserveBody,
            controllerAddress = "192.168.1.20",
            localAddress = "192.168.1.50",
        )
        assertEquals(401, plainTokenStatus)
        assertTrue(server.isPeerAuthorized("192.168.42.10"))
        assertFalse(server.isPeerAuthorized("192.168.1.20"))

        // A proof computed for a spoofed attacker IP cannot be replayed to the real phone IP.
        val nextToken = "fedcba9876543210fedcba9876543210"
        val attackerProof = CameraControlServer.computeRebindProof(
            initialToken,
            "192.168.1.99",
            nextToken,
        )
        val spoofedProofBody =
            """{"sourceInstanceId":"obs-1","reservationToken":"$nextToken","rebindProof":"$attackerProof","slotLabel":"CAM A","bitrateMbps":12}"""
        val (spoofedStatus, _) = server.handleReserveRequest(
            spoofedProofBody,
            controllerAddress = "192.168.1.99",
            localAddress = "192.168.1.50",
        )
        assertEquals(401, spoofedStatus)

        // A proof bound to the real phone's Wi-Fi IP succeeds, rotates the token,
        // and returns an authenticated rebindAck.
        val validProof = CameraControlServer.computeRebindProof(
            initialToken,
            "192.168.1.50",
            nextToken,
        )
        val validRebindBody =
            """{"sourceInstanceId":"obs-1","reservationToken":"$nextToken","rebindProof":"$validProof","slotLabel":"CAM A","bitrateMbps":12}"""
        val (rebindStatus, rebindResponse) = server.handleReserveRequest(
            validRebindBody,
            controllerAddress = "192.168.1.20",
            localAddress = "192.168.1.50",
        )
        assertEquals(200, rebindStatus)
        assertEquals(
            CameraControlServer.computeRebindAck(initialToken, "192.168.1.50", nextToken),
            JSONObject(rebindResponse).getString("rebindAck"),
        )
        assertTrue(server.isPeerAuthorized("192.168.1.20"))
        assertFalse(server.isPeerAuthorized("192.168.42.10"))

        // If the 200 OK reply was lost in transit and OBS returns directly to the
        // original USB route instead of retrying Wi-Fi, proving the prior token
        // for the USB local address recovers control immediately.
        val usbFallbackToken = "223344556677889900aabbccddeeff11"
        val usbFallbackProof = CameraControlServer.computeRebindProof(
            initialToken,
            "192.168.42.129",
            usbFallbackToken,
        )
        val usbFallbackBody =
            """{"sourceInstanceId":"obs-1","reservationToken":"$usbFallbackToken","rebindProof":"$usbFallbackProof","slotLabel":"CAM A","bitrateMbps":12}"""
        val (usbFallbackStatus, usbFallbackResponse) = server.handleReserveRequest(
            usbFallbackBody,
            controllerAddress = "192.168.42.10",
            localAddress = "192.168.42.129",
        )
        assertEquals(200, usbFallbackStatus)
        assertEquals(
            CameraControlServer.computeRebindAck(initialToken, "192.168.42.129", usbFallbackToken),
            JSONObject(usbFallbackResponse).getString("rebindAck"),
        )
        assertTrue(server.isPeerAuthorized("192.168.42.10"))

        // If the 200 OK reply was lost in transit, retrying from the Wi-Fi peer with
        // the prior token's proof also succeeds idempotently and returns a valid rebindAck.
        val retryToken = "11223344556677889900aabbccddeeff"
        val retryProof = CameraControlServer.computeRebindProof(
            initialToken,
            "192.168.1.50",
            retryToken,
        )
        val retryBody =
            """{"sourceInstanceId":"obs-1","reservationToken":"$retryToken","rebindProof":"$retryProof","slotLabel":"CAM A","bitrateMbps":12}"""
        val (retryStatus, retryResponse) = server.handleReserveRequest(
            retryBody,
            controllerAddress = "192.168.1.20",
            localAddress = "192.168.1.50",
        )
        assertEquals(200, retryStatus)
        assertEquals(
            CameraControlServer.computeRebindAck(initialToken, "192.168.1.50", retryToken),
            JSONObject(retryResponse).getString("rebindAck"),
        )

        // A different peer cannot replay a captured prior-token proof to hijack control.
        val (replayStatus, _) = server.handleReserveRequest(
            validRebindBody,
            controllerAddress = "192.168.1.99",
            localAddress = "192.168.1.50",
        )
        assertEquals(401, replayStatus)
        assertTrue(server.isPeerAuthorized("192.168.1.20"))
        assertFalse(server.isPeerAuthorized("192.168.1.99"))

        // Once OBS rebinds again using the rotated token, the original token is retired.
        val usbReturnToken = "ffeeddccbbaa00998877665544332211"
        val usbReturnProof = CameraControlServer.computeRebindProof(
            retryToken,
            "192.168.42.129",
            usbReturnToken,
        )
        val usbReturnBody =
            """{"sourceInstanceId":"obs-1","reservationToken":"$usbReturnToken","rebindProof":"$usbReturnProof","slotLabel":"CAM A","bitrateMbps":12}"""
        val (usbReturnStatus, usbReturnResponse) = server.handleReserveRequest(
            usbReturnBody,
            controllerAddress = "192.168.42.10",
            localAddress = "192.168.42.129",
        )
        assertEquals(200, usbReturnStatus)
        assertEquals(
            CameraControlServer.computeRebindAck(retryToken, "192.168.42.129", usbReturnToken),
            JSONObject(usbReturnResponse).getString("rebindAck"),
        )
        assertTrue(server.isPeerAuthorized("192.168.42.10"))

        val (retiredStatus, _) = server.handleReserveRequest(
            validRebindBody,
            controllerAddress = "192.168.1.20",
            localAddress = "192.168.1.50",
        )
        assertEquals(401, retiredStatus)
    }
}

