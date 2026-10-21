package dev.openstream.app.irl

import org.junit.Assert.*
import org.junit.Test

class IrlProfileTest {
    @Test
    fun rtmpNormalizesTrailingSlashesAndEncodesKeysAsPaths() {
        assertEquals("rtmp://live.example/app/key", PublishUrl.rtmp("rtmp://live.example/app///", "/key///"))
        assertEquals("rtmp://live.example/app/key%3Ftoken=a%20b", PublishUrl.rtmp("rtmp://live.example/app/", "key?token=a b"))
        assertEquals("rtmp://live.example/app/key%23%25", PublishUrl.rtmp("rtmp://live.example/app", "key#%"))
    }

    @Test
    fun rtmpPreservesPortsIpv6AndTheOriginalQuery() {
        assertEquals(
            "rtmps://live.example:443/app/key?token=a%2Fb&x=1",
            PublishUrl.rtmp("rtmps://live.example:443/app/?token=a%2Fb&x=1", "key"),
        )
        assertEquals("rtmp://[::1]:1935/app/key", PublishUrl.rtmp("rtmp://[::1]:1935/app", "key"))
        assertEquals("rtmps://[::1]/app/key?auth=token", PublishUrl.rtmp("rtmps://[::1]/app/key/?auth=token", ""))
    }

    @Test
    fun rtmpRejectsUserInfoFragmentsMissingPathsAndInvalidHostsOrPorts() {
        listOf(
            "rtmp://user:secret@live.example/app",
            "rtmp://live.example/app#fragment",
            "https://live.example/app",
            "rtmp://live.example",
            "rtmp:///app",
            "rtmp://live.example:0/app",
            "rtmp://live.example:65536/app",
        ).forEach { server ->
            assertThrows(server, IllegalArgumentException::class.java) { PublishUrl.rtmp(server, "key") }
        }
        assertThrows(IllegalArgumentException::class.java) { PublishUrl.rtmp("rtmp://live.example/app", "") }
    }

    @Test
    fun srtDecodesAndReencodesStreamIdAndPassphraseWithoutTreatingPlusAsSpace() {
        val result = PublishUrl.srt("srt://[::1]:9000?streamid=%23!%3A%3Ar%3Dlive%2Fa%20b%2Bc&passphrase=pass%20word%2B123&pbkeylen=32")

        assertEquals("#!::r=live/a b+c", result.streamId)
        assertEquals("pass word+123", result.passphrase)
        assertEquals(32, result.pbkeylen)
        assertEquals(2000, result.latencyMs)
        assertEquals(
            "srt://[::1]:9000?streamid=%23%21%3A%3Ar%3Dlive%2Fa%20b%2Bc&passphrase=pass%20word%2B123&pbkeylen=32&mode=caller&latency=2000&irl=1",
            result.url,
        )
        assertEquals("a+b", PublishUrl.srt("srt://live.example:9000?streamid=a+b").streamId)
    }

    @Test
    fun srtRejectsDuplicateUnknownAndNonCallerOptions() {
        listOf(
            "streamid=a&streamid=b", "streamid=a&%73treamid=b", "mode=listener", "mode=rendezvous",
            "latency=1000&latency=2000", "unknown=value", "streamid=%00", "streamid=${"a".repeat(513)}",
        ).forEach { query ->
            assertThrows(query, IllegalArgumentException::class.java) { PublishUrl.srt("srt://live.example:9000?$query") }
        }
    }

    @Test
    fun srtClampsLatencyAndRejectsNonNumericLatency() {
        mapOf("-1" to 500, "499" to 500, "500" to 500, "2400" to 2400, "5000" to 5000, "9000" to 5000).forEach { (value, expected) ->
            val result = PublishUrl.srt("srt://live.example:9000?latency=$value")
            assertEquals(expected, result.latencyMs)
            assertTrue(result.url.contains("latency=$expected"))
        }
        listOf("", "slow", "2147483648").forEach { latency ->
            assertThrows(IllegalArgumentException::class.java) { PublishUrl.srt("srt://live.example:9000?latency=$latency") }
        }
    }

    @Test
    fun srtPassphraseLimitsCountUtf8BytesAndPbkeylenAllowsOnlyAesSizes() {
        listOf(10, 79).forEach { length ->
            val passphrase = "a".repeat(length)
            assertEquals(passphrase, PublishUrl.srt("srt://live.example:9000?passphrase=$passphrase").passphrase)
        }
        listOf("", "a".repeat(9), "a".repeat(80), "%C3%A9".repeat(40)).forEach { passphrase ->
            assertThrows(IllegalArgumentException::class.java) { PublishUrl.srt("srt://live.example:9000?passphrase=$passphrase") }
        }
        assertEquals("é".repeat(5), PublishUrl.srt("srt://live.example:9000?passphrase=${"%C3%A9".repeat(5)}").passphrase)
        assertEquals(16, PublishUrl.srt("srt://live.example:9000").pbkeylen)
        listOf(16, 24, 32).forEach { length ->
            assertEquals(length, PublishUrl.srt("srt://live.example:9000?pbkeylen=$length").pbkeylen)
        }
        listOf("", "0", "128", "abc").forEach { length ->
            assertThrows(IllegalArgumentException::class.java) { PublishUrl.srt("srt://live.example:9000?pbkeylen=$length") }
        }
    }

    @Test
    fun srtRequiresAHostAndPortAndRejectsPathsUserInfoAndFragments() {
        listOf(
            "srt://live.example", "srt://live.example:0", "srt://live.example:65536",
            "srt://live.example:9000/path", "srt://user:secret@live.example:9000",
            "srt://live.example:9000#fragment", "rtmp://live.example:9000",
        ).forEach { server ->
            assertThrows(server, IllegalArgumentException::class.java) { PublishUrl.srt(server) }
        }
    }

    @Test
    fun settingsBuildPublishUrlsForEveryPlatform() {
        val servers = mapOf(
            LivePlatform.TWITCH to "rtmps://ingest.global-contribute.live-video.net/app",
            LivePlatform.YOUTUBE to "rtmps://a.rtmps.youtube.com:443/live2",
            LivePlatform.KICK to "rtmps://kick.example:443/app",
            LivePlatform.FACEBOOK to "rtmps://rtmp-api.facebook.com:443/rtmp",
            LivePlatform.CUSTOM_RTMP to "rtmp://custom.example/app",
        )
        servers.forEach { (platform, server) ->
            val input = if (platform.server.isBlank()) server else platform.server
            assertEquals("$server/secret", LiveSettings(platform, input, "secret").publishUrl())
            assertThrows(IllegalArgumentException::class.java) { LiveSettings(platform, server, "").publishUrl() }
        }
        assertEquals(
            "rtmps://custom.example/app/secret?auth=token",
            LiveSettings(LivePlatform.CUSTOM_RTMP, "rtmps://custom.example/app/secret/?auth=token").publishUrl(),
        )
        assertEquals(
            "srt://custom.example:9000?mode=caller&latency=2000&irl=1",
            LiveSettings(LivePlatform.CUSTOM_SRT, "srt://custom.example:9000").publishUrl(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            LiveSettings(LivePlatform.CUSTOM_SRT, "srt://custom.example:9000", "secret").publishUrl()
        }
    }

    @Test
    fun qualityUsesItsRequestedBitrateUpToThePlatformCeiling() {
        val requested = listOf(1_800_000, 3_500_000, 5_500_000, 6_500_000)
        LiveQuality.entries.forEachIndexed { index, quality ->
            LivePlatform.entries.forEach { platform ->
                val expected = if (platform == LivePlatform.TWITCH && quality == LiveQuality.P1080) 6_000_000 else requested[index]
                assertEquals("$platform $quality", expected, quality.bitrate(platform))
                assertTrue(quality.bitrate(platform) <= platform.ceilingKbps * 1000)
            }
        }
    }

    @Test
    fun redactionRemovesCredentialsFromPathsQueriesUserInfoAndInvalidInputs() {
        val key = "private-stream-key"
        listOf(
            "rtmps://live.example:443/app/$key?auth=$key",
            "rtmp://$key@live.example/app/$key",
            "srt://live.example:9000?streamid=$key&passphrase=$key",
            "rtmp://[::1]:1935/app/$key",
            "rtmp://live.example/app/$key#fragment",
            "invalid url $key", "https://live.example/$key",
        ).forEach { url ->
            val redacted = PublishRedaction.endpoint(url)
            assertFalse(redacted, redacted.contains(key))
            assertFalse(redacted.contains("auth="))
            assertFalse(redacted.contains("passphrase="))
        }
        assertEquals("rtmps://live.example:443/[redacted]", PublishRedaction.endpoint("rtmps://live.example:443/app/$key"))
        assertEquals("[redacted endpoint]", PublishRedaction.endpoint("not a url"))
    }
}
