package dev.openstream.app.stream

import org.json.JSONObject

/**
 * Point-in-time health of the outgoing stream. Produced on the phone once per
 * second and published to OBS as the `link` object of `GET /status`.
 *
 * Wire contract (all keys always present, OBS treats a missing `link` object as
 * "no stats yet"):
 *
 * ```json
 * "link": {
 *   "rttMs": 4.2,
 *   "lossPercent": 0.3,
 *   "sendRateKbps": 11850,
 *   "targetBitrateKbps": 12000,
 *   "maxBitrateKbps": 12000,
 *   "availableBandwidthKbps": 85000,
 *   "sendBufferMs": 12,
 *   "droppedFrames": 0,
 *   "fps": 30,
 *   "codec": "avc",
 *   "thermal": "none"
 * }
 * ```
 *
 * `thermal` is one of none|light|moderate|severe|critical|emergency|shutdown.
 * `codec` is "avc" or "hevc".
 */
data class StreamHealth(
    val rttMs: Double,
    val lossPercent: Double,
    val sendRateKbps: Int,
    val targetBitrateKbps: Int,
    val maxBitrateKbps: Int,
    val availableBandwidthKbps: Int,
    val sendBufferMs: Int,
    val droppedFrames: Long,
    val fps: Int,
    val codec: String,
    val thermal: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("rttMs", rttMs)
        .put("lossPercent", lossPercent)
        .put("sendRateKbps", sendRateKbps)
        .put("targetBitrateKbps", targetBitrateKbps)
        .put("maxBitrateKbps", maxBitrateKbps)
        .put("availableBandwidthKbps", availableBandwidthKbps)
        .put("sendBufferMs", sendBufferMs)
        .put("droppedFrames", droppedFrames)
        .put("fps", fps)
        .put("codec", codec)
        .put("thermal", thermal)
}
