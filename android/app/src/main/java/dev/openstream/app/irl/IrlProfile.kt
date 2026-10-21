package dev.openstream.app.irl

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class LivePlatform(val label: String, val server: String, val ceilingKbps: Int) {
    TWITCH("Twitch", "rtmps://ingest.global-contribute.live-video.net/app", 6000),
    YOUTUBE("YouTube", "rtmps://a.rtmps.youtube.com:443/live2", 20000),
    KICK("Kick", "", 8000),
    FACEBOOK("Facebook", "rtmps://rtmp-api.facebook.com:443/rtmp/", 9000),
    CUSTOM_RTMP("Custom RTMP(S)", "", 20000),
    CUSTOM_SRT("Custom SRT", "", 20000),
}

enum class LiveQuality(val label: String, val width: Int, val height: Int, val fps: Int, val kbps: Int) {
    P480("480p30", 854, 480, 30, 1800),
    P720("720p30", 1280, 720, 30, 3500),
    P720_60("720p60", 1280, 720, 60, 5500),
    P1080("1080p30", 1920, 1080, 30, 6500);

    fun bitrate(platform: LivePlatform): Int = minOf(kbps, platform.ceilingKbps) * 1000
}

data class LiveSettings(
    val platform: LivePlatform = LivePlatform.TWITCH,
    val server: String = LivePlatform.TWITCH.server,
    val key: String = "",
    val quality: LiveQuality = LiveQuality.P720,
) {
    fun publishUrl(): String = if (platform == LivePlatform.CUSTOM_SRT) {
        require(key.isBlank()) { "Put SRT credentials in the server URL; leave stream key empty." }
        PublishUrl.srt(server).url
    } else {
        require(platform == LivePlatform.CUSTOM_RTMP || key.isNotBlank()) { "Enter your stream key." }
        PublishUrl.rtmp(server, key)
    }
}

data class SrtPublishUrl(val url: String, val streamId: String?, val passphrase: String?, val latencyMs: Int, val pbkeylen: Int)

object PublishUrl {
    /** The bundled libsrt is built without HaiCrypt (SRT_ENABLE_ENCRYPTION off). */
    const val SRT_ENCRYPTION_SUPPORTED = false
    const val SRT_PASSPHRASE_UNSUPPORTED = "This beta's SRT doesn't support passphrases yet. Remove passphrase= from the URL."

    private fun uri(value: String): URI {
        val uri = try { URI(value.trim()) } catch (_: Exception) {
            throw IllegalArgumentException("Enter a valid server URL.")
        }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535)) { "Enter a server host and valid port, without user info or a fragment." }
        return uri
    }

    fun rtmp(server: String, key: String): String {
        val uri = uri(server)
        require(uri.scheme == "rtmp" || uri.scheme == "rtmps") { "Use an rtmp:// or rtmps:// server." }
        val streamKey = key.trim().trim('/')
        val path = uri.rawPath.orEmpty().trimEnd('/') + if (streamKey.isEmpty()) "" else {
            "/" + URI(null, null, "/$streamKey", null).rawPath.removePrefix("/")
        }
        require(path.split('/').count { it.isNotBlank() } >= 2) { "Enter an application path and stream key, or a full publish URL." }
        return "${uri.scheme}://${uri.rawAuthority}$path" + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    fun srt(server: String, encryptionSupported: Boolean = SRT_ENCRYPTION_SUPPORTED): SrtPublishUrl {
        val uri = uri(server)
        require(uri.scheme == "srt" && uri.port in 1..65535 && uri.path.isNullOrEmpty()) { "Use srt://host:port?streamid=…" }
        val values = linkedMapOf<String, String>()
        try {
            uri.rawQuery?.split('&')?.filter { it.isNotBlank() }?.forEach { part ->
                val pair = part.split('=', limit = 2)
                val name = decode(pair[0])
                require(name in setOf("mode", "streamid", "passphrase", "pbkeylen", "latency"))
                require(name !in values)
                values[name] = decode(pair.getOrElse(1) { "" })
            }
        } catch (_: Exception) { throw IllegalArgumentException("Invalid or unsupported SRT query option.") }
        require(values["mode"] == null || values["mode"] == "caller") { "IRL SRT supports caller mode only." }
        val latency = values["latency"]?.let { requireNotNull(it.toIntOrNull()) { "Latency must be milliseconds." } }
            ?.coerceIn(500, 5000) ?: 2000
        val passphrase = values["passphrase"]
        // Reject before connecting; the native CONFIG error remains the backstop.
        require(passphrase == null || encryptionSupported) { SRT_PASSPHRASE_UNSUPPORTED }
        require(passphrase == null || passphrase.toByteArray(StandardCharsets.UTF_8).size in 10..79) { "SRT passphrase must be 10–79 UTF-8 bytes." }
        val pbkeylen = values["pbkeylen"]?.toIntOrNull() ?: if (values.containsKey("pbkeylen")) 0 else 16
        require(pbkeylen in listOf(16, 24, 32)) { "SRT pbkeylen must be 16, 24 or 32." }
        require((values["streamid"]?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0) <= 512) { "SRT streamid is too long." }
        require(values.values.none { '\u0000' in it }) { "SRT options cannot contain a null byte." }
        values["mode"] = "caller"
        values["latency"] = latency.toString()
        values["irl"] = "1"
        val query = values.entries.joinToString("&") { "${it.key}=${encode(it.value)}" }
        return SrtPublishUrl("srt://${uri.rawAuthority}?$query", values["streamid"], passphrase, latency, pbkeylen)
    }

    private fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

object PublishRedaction {
    // Publish paths, query values and server responses can all contain credentials.
    fun endpoint(url: String): String = runCatching {
        val uri = URI(url)
        require(uri.scheme in listOf("rtmp", "rtmps", "srt") && uri.host != null)
        "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}/[redacted]"
    }.getOrDefault("[redacted endpoint]")
}
