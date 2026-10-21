package dev.openstream.app.irl

import java.util.Locale

/** Pure formatting and validation for the IRL screen; free of Android types so it runs in JVM tests. */
object IrlUi {
    enum class Field { SERVER, KEY }

    data class Validation(val publishUrl: String?, val field: Field?, val message: String?) {
        val valid: Boolean get() = publishUrl != null
    }

    enum class Tone { IDLE, PENDING, LIVE, ERROR }

    data class StatusPill(val text: String, val tone: Tone)

    private const val KEY_MISSING = "Enter your stream key."
    private const val SRT_KEY_PRESENT = "Put SRT credentials in the server URL; leave stream key empty."
    private const val FALLBACK = "Check the server URL and stream key."

    fun isActive(status: LiveStatus): Boolean =
        status is LiveStatus.Connecting || status is LiveStatus.Live || status is LiveStatus.Reconnecting

    fun statusPill(status: LiveStatus, nowElapsedMs: Long): StatusPill = when (status) {
        LiveStatus.Idle -> StatusPill("Ready", Tone.IDLE)
        LiveStatus.Connecting -> StatusPill("Connecting…", Tone.PENDING)
        is LiveStatus.Live -> StatusPill("LIVE • " + duration(nowElapsedMs - status.startedAtElapsedMs), Tone.LIVE)
        is LiveStatus.Reconnecting -> StatusPill(
            if (status.retryInSeconds > 0) "Reconnecting in ${status.retryInSeconds}s" else "Reconnecting…",
            Tone.PENDING,
        )
        is LiveStatus.Error -> StatusPill(status.message.ifBlank { "Stream stopped" }, Tone.ERROR)
    }

    /** HH:MM:SS; negative spans (clock skew between HUD and UI) read as zero. */
    fun duration(elapsedMs: Long): String {
        val total = elapsedMs.coerceAtLeast(0) / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
    }

    fun bitrate(bps: Long): String = when {
        bps >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
        bps >= 1_000 -> "${bps / 1_000} kbps"
        bps > 0 -> "$bps bps"
        else -> "0 kbps"
    }

    /** "4.2 / 6.0 Mbps": what the transport is sending against what the encoder is asked for. */
    fun sentVsTarget(sentBps: Long, targetBps: Int): String {
        if (targetBps <= 0) return bitrate(sentBps)
        val unitMbps = targetBps >= 1_000_000
        return if (unitMbps) {
            String.format(Locale.US, "%.1f / %.1f Mbps", sentBps / 1_000_000.0, targetBps / 1_000_000.0)
        } else {
            "${sentBps / 1_000} / ${targetBps / 1_000} kbps"
        }
    }

    fun fps(fps: Int): String = "${fps.coerceAtLeast(0)} fps"

    fun dropped(frames: Long): String = if (frames == 1L) "1 dropped" else "${frames.coerceAtLeast(0)} dropped"

    fun serverHint(platform: LivePlatform): String = when (platform) {
        LivePlatform.KICK -> "rtmps://fa723fc1b171.global-contribute.live-video.net:443/app"
        LivePlatform.CUSTOM_RTMP -> "rtmp://host/app"
        LivePlatform.CUSTOM_SRT -> "srt://host:port?streamid=…"
        else -> platform.server
    }

    fun keyHint(platform: LivePlatform): String =
        if (platform == LivePlatform.CUSTOM_RTMP) "Optional if the URL already has it" else "Paste from your ${platform.label} dashboard"

    fun showsKey(platform: LivePlatform): Boolean = platform != LivePlatform.CUSTOM_SRT

    /** Typed servers belong to their saved platform; only quality carries across platforms. */
    fun settingsForPlatformSwitch(current: LiveSettings, destination: LiveSettings): LiveSettings =
        destination.copy(
            server = destination.server.ifBlank { destination.platform.server },
            quality = current.quality,
        )

    fun qualityDetail(quality: LiveQuality, platform: LivePlatform): String {
        val effective = quality.bitrate(platform)
        val base = "${quality.width}×${quality.height} · ${quality.fps} fps · ${bitrate(effective.toLong())}"
        return if (effective < quality.kbps * 1000) "$base (${platform.label} max)" else base
    }

    /** Builds the settings the form describes; Custom SRT never carries a key field. */
    fun settings(platform: LivePlatform, server: String, key: String, quality: LiveQuality): LiveSettings =
        LiveSettings(platform, server.trim(), if (showsKey(platform)) key.trim() else "", quality)

    fun validate(settings: LiveSettings): Validation {
        if (settings.server.isBlank()) {
            val message = when (settings.platform) {
                LivePlatform.KICK -> "Paste the Stream URL from your Kick dashboard."
                LivePlatform.CUSTOM_SRT -> "Enter an srt://host:port server."
                else -> "Enter the ingest server URL."
            }
            return Validation(null, Field.SERVER, message)
        }
        return try {
            Validation(settings.publishUrl(), null, null)
        } catch (e: IllegalArgumentException) {
            failure(e.message)
        } catch (e: IllegalStateException) {
            failure(e.message)
        }
    }

    private fun failure(message: String?): Validation = when {
        message == KEY_MISSING || message == SRT_KEY_PRESENT -> Validation(null, Field.KEY, message)
        message.isNullOrBlank() -> Validation(null, Field.SERVER, FALLBACK)
        else -> Validation(null, Field.SERVER, message)
    }

    fun confirmMessage(settings: LiveSettings, publishUrl: String, micAllowed: Boolean): String = buildString {
        append(PublishRedaction.endpoint(publishUrl))
        append("\n")
        append(settings.quality.label).append(" · ").append(bitrate(settings.quality.bitrate(settings.platform).toLong()))
        if (!micAllowed) append("\nMicrophone not allowed: video only.")
    }
}
