package dev.openstream.app.stream

import dev.openstream.app.encoder.advertisedMimeType
import org.json.JSONObject

/** Missing/legacy capabilities can only reserve SDR profiles up to 1080p. */
object ReceiverCapabilities {
    fun accepts(json: JSONObject?, config: StreamConfig): Boolean {
        if (!config.requiresUsb) return true
        if (json == null || json.opt("version") != 1) return false
        fun supports(key: String, value: String): Boolean {
            val array = json.optJSONArray(key) ?: return false
            return (0 until array.length()).any { array.opt(it) == value }
        }
        fun limit(key: String, needed: Int): Boolean =
            (json.opt(key) as? Int)?.let { it >= needed } == true
        return supports("codecs", config.codecPreference.advertisedMimeType()) &&
            supports("dynamicRanges", config.dynamicRange) &&
            limit("maxWidth", config.width) && limit("maxHeight", config.height) && limit("maxFps", config.fps)
    }

    fun session(config: StreamConfig): JSONObject = JSONObject()
        .put("codec", config.codecPreference.advertisedMimeType())
        .put("width", config.width).put("height", config.height).put("fps", config.fps)
        .put("dynamicRange", config.dynamicRange)
}
