package dev.openstream.app.irl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrlUiTest {
    @Test
    fun durationFormatsHoursMinutesSeconds() {
        assertEquals("00:00:00", IrlUi.duration(0))
        assertEquals("00:00:59", IrlUi.duration(59_999))
        assertEquals("00:12:34", IrlUi.duration((12 * 60 + 34) * 1000L))
        assertEquals("27:46:40", IrlUi.duration(100_000_000))
        assertEquals("00:00:00", IrlUi.duration(-5_000))
    }

    @Test
    fun statusPillDescribesEachState() {
        assertEquals(IrlUi.StatusPill("Ready", IrlUi.Tone.IDLE), IrlUi.statusPill(LiveStatus.Idle, 0))
        assertEquals(IrlUi.Tone.PENDING, IrlUi.statusPill(LiveStatus.Connecting, 0).tone)
        assertEquals(
            IrlUi.StatusPill("LIVE • 00:12:34", IrlUi.Tone.LIVE),
            IrlUi.statusPill(LiveStatus.Live(startedAtElapsedMs = 1_000), 1_000 + 754_000),
        )
        assertEquals(
            IrlUi.StatusPill("Reconnecting in 4s", IrlUi.Tone.PENDING),
            IrlUi.statusPill(LiveStatus.Reconnecting(4, "timeout"), 0),
        )
        assertEquals("Reconnecting…", IrlUi.statusPill(LiveStatus.Reconnecting(0, "timeout"), 0).text)
        assertEquals(
            IrlUi.StatusPill("Ingest rejected the key", IrlUi.Tone.ERROR),
            IrlUi.statusPill(LiveStatus.Error("Ingest rejected the key"), 0),
        )
        assertEquals("Stream stopped", IrlUi.statusPill(LiveStatus.Error(" "), 0).text)
    }

    @Test
    fun onlyConnectingLiveAndReconnectingAreActive() {
        assertFalse(IrlUi.isActive(LiveStatus.Idle))
        assertFalse(IrlUi.isActive(LiveStatus.Error("x")))
        assertTrue(IrlUi.isActive(LiveStatus.Connecting))
        assertTrue(IrlUi.isActive(LiveStatus.Live(0)))
        assertTrue(IrlUi.isActive(LiveStatus.Reconnecting(1, "x")))
    }

    @Test
    fun bitratesUseReadableUnits() {
        assertEquals("0 kbps", IrlUi.bitrate(0))
        assertEquals("850 bps", IrlUi.bitrate(850))
        assertEquals("850 kbps", IrlUi.bitrate(850_400))
        assertEquals("3.5 Mbps", IrlUi.bitrate(3_500_000))
        assertEquals("4.2 / 6.0 Mbps", IrlUi.sentVsTarget(4_200_000, 6_000_000))
        assertEquals("300 / 800 kbps", IrlUi.sentVsTarget(300_000, 800_000))
        assertEquals("1.2 Mbps", IrlUi.sentVsTarget(1_200_000, 0))
        assertEquals("30 fps", IrlUi.fps(30))
        assertEquals("1 dropped", IrlUi.dropped(1))
        assertEquals("12 dropped", IrlUi.dropped(12))
    }

    @Test
    fun qualityDetailShowsPlatformCap() {
        assertEquals("1280×720 · 30 fps · 3.5 Mbps", IrlUi.qualityDetail(LiveQuality.P720, LivePlatform.TWITCH))
        assertEquals("1920×1080 · 30 fps · 6.0 Mbps (Twitch max)", IrlUi.qualityDetail(LiveQuality.P1080, LivePlatform.TWITCH))
        assertEquals("1920×1080 · 30 fps · 6.5 Mbps", IrlUi.qualityDetail(LiveQuality.P1080, LivePlatform.YOUTUBE))
    }

    @Test
    fun hintsMatchPlatform() {
        assertEquals("rtmps://fa723fc1b171.global-contribute.live-video.net:443/app", IrlUi.serverHint(LivePlatform.KICK))
        assertEquals("srt://host:port?streamid=…", IrlUi.serverHint(LivePlatform.CUSTOM_SRT))
        assertEquals(LivePlatform.TWITCH.server, IrlUi.serverHint(LivePlatform.TWITCH))
        assertFalse(IrlUi.showsKey(LivePlatform.CUSTOM_SRT))
        assertTrue(IrlUi.showsKey(LivePlatform.CUSTOM_RTMP))
    }

    @Test
    fun platformSwitchReplacesPresetsButKeepsTypedServers() {
        assertEquals(LivePlatform.YOUTUBE.server, IrlUi.serverForPlatformSwitch(LivePlatform.TWITCH.server, LivePlatform.YOUTUBE))
        assertEquals(LivePlatform.FACEBOOK.server, IrlUi.serverForPlatformSwitch("  ", LivePlatform.FACEBOOK))
        assertEquals("", IrlUi.serverForPlatformSwitch(LivePlatform.TWITCH.server, LivePlatform.KICK))
        val typed = "rtmp://my.box/live"
        assertEquals(typed, IrlUi.serverForPlatformSwitch(typed, LivePlatform.CUSTOM_SRT))
    }

    @Test
    fun customSrtFormDropsAnyHiddenKey() {
        val settings = IrlUi.settings(LivePlatform.CUSTOM_SRT, " srt://host:9000?streamid=a ", "leftover", LiveQuality.P720)
        assertEquals("", settings.key)
        assertEquals("srt://host:9000?streamid=a", settings.server)
        assertTrue(IrlUi.validate(settings).valid)
    }

    @Test
    fun validationMapsFailuresToFields() {
        val missingKey = IrlUi.validate(LiveSettings(LivePlatform.TWITCH, LivePlatform.TWITCH.server, "", LiveQuality.P720))
        assertNull(missingKey.publishUrl)
        assertEquals(IrlUi.Field.KEY, missingKey.field)
        assertEquals("Enter your stream key.", missingKey.message)

        val kick = IrlUi.validate(LiveSettings(LivePlatform.KICK, "", "sk_live", LiveQuality.P720))
        assertEquals(IrlUi.Field.SERVER, kick.field)
        assertEquals("Paste the Stream URL from your Kick dashboard.", kick.message)

        val wrongScheme = IrlUi.validate(LiveSettings(LivePlatform.CUSTOM_RTMP, "http://host/app", "key", LiveQuality.P720))
        assertEquals(IrlUi.Field.SERVER, wrongScheme.field)
        assertEquals("Use an rtmp:// or rtmps:// server.", wrongScheme.message)

        val srtKey = IrlUi.validate(LiveSettings(LivePlatform.CUSTOM_SRT, "srt://host:9000", "key", LiveQuality.P720))
        assertEquals(IrlUi.Field.KEY, srtKey.field)

        val badLatency = IrlUi.validate(LiveSettings(LivePlatform.CUSTOM_SRT, "srt://host:9000?latency=soon", "", LiveQuality.P720))
        assertEquals(IrlUi.Field.SERVER, badLatency.field)
        assertEquals("Latency must be milliseconds.", badLatency.message)
    }

    @Test
    fun validMessagesNeverEchoTheKey() {
        val key = "live_123456_secret"
        val settings = LiveSettings(LivePlatform.TWITCH, LivePlatform.TWITCH.server, key, LiveQuality.P1080)
        val validation = IrlUi.validate(settings)
        assertTrue(validation.valid)
        val message = IrlUi.confirmMessage(settings, validation.publishUrl!!, micAllowed = false)
        assertFalse(key in message)
        assertEquals(
            "rtmps://ingest.global-contribute.live-video.net/[redacted]\n1080p30 · 6.0 Mbps\nMicrophone not allowed: video only.",
            message,
        )
    }
}
