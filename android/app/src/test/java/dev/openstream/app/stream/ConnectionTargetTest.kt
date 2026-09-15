package dev.openstream.app.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionTargetTest {
    @Test
    fun srtCallerUrlLeavesIpv4AndHostnameUnchanged() {
        assertEquals(
            "srt://192.168.1.20:9000?mode=caller&latency=120",
            ConnectionTarget("obs", "192.168.1.20", 9000, 120).toSrtCallerUrl(),
        )
        assertEquals(
            "srt://obs.local:9000?mode=caller&latency=120",
            ConnectionTarget("obs", "obs.local", 9000, 120).toSrtCallerUrl(),
        )
    }

    @Test
    fun srtCallerUrlBracketsUnbracketedIpv6Literals() {
        assertEquals(
            "srt://[2001:db8::1]:9000?mode=caller&latency=120",
            ConnectionTarget("obs", "2001:db8::1", 9000, 120).toSrtCallerUrl(),
        )
        assertEquals(
            "srt://[::1]:9000?mode=caller&latency=120",
            ConnectionTarget("obs", "::1", 9000, 120).toSrtCallerUrl(),
        )
    }

    @Test
    fun srtCallerUrlDoesNotDoubleBracketIpv6Literals() {
        assertEquals(
            "srt://[2001:db8::1]:9000?mode=caller&latency=120",
            ConnectionTarget("obs", "[2001:db8::1]", 9000, 120).toSrtCallerUrl(),
        )
    }
}
