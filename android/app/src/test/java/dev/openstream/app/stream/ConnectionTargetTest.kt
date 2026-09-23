// android.net.Uri.parse is an unmocked Android stub in local JVM tests. Keep
// this test in android.net so its adapter can call Uri's package-private constructor.
package android.net

import android.os.Parcel
import dev.openstream.app.stream.ConnectionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class ConnectionTargetTest {
    @Test
    fun publishedOpenStreamPairingUriParses() {
        val target = ConnectionTarget.fromPairingUri(
            TestUri("openstream://connect?host=192.168.1.20&port=9100&latency=120&name=CAM%20A"),
        )

        assertNotNull(target)
        assertEquals(ConnectionTarget("CAM A", "192.168.1.20", 9100, 120), target)
    }

    @Test
    fun currentShinPairingUriRemainsSupported() {
        assertEquals(
            ConnectionTarget("CAM A", "192.168.1.20", 9100, 120),
            ConnectionTarget.fromPairingUri(
                TestUri("shin://connect?host=192.168.1.20&port=9100&latency=120&name=CAM%20A"),
            ),
        )
    }

    @Test
    fun pairingUriRejectsMalformedOrOversizedAuthorityAndInputs() {
        val invalidUris = listOf(
            "openstream://user@connect?host=192.168.1.20",
            "openstream://connect:80?host=192.168.1.20",
            "openstream://connect/path?host=192.168.1.20",
            "openstream://connect?host=",
            "openstream://connect?host=${"h".repeat(254)}",
            "openstream://connect?host=192.168.1.20&name=",
            "openstream://connect?host=192.168.1.20&name=${"n".repeat(129)}",
            "openstream://connect?host=192.168.1.20&x=${"x".repeat(1025)}",
            "openstream://connect?host=192.168.1.20&port=0",
            "openstream://connect?host=192.168.1.20&port=65536",
            "openstream://connect?host=192.168.1.20&port=invalid",
            "openstream://connect?host=192.168.1.20&latency=79",
            "openstream://connect?host=192.168.1.20&latency=201",
            "openstream://connect?host=192.168.1.20&latency=invalid",
        )

        invalidUris.forEach { raw ->
            assertNull(raw, ConnectionTarget.fromPairingUri(TestUri(raw)))
        }
    }

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

private class TestUri(private val raw: String) : Uri() {
    private val parsed = java.net.URI(raw)
    private val query = parsed.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate { item ->
        val (key, value) = item.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
    }

    override fun isHierarchical() = true
    override fun isRelative() = false
    override fun getScheme() = parsed.scheme
    override fun getSchemeSpecificPart() = parsed.rawSchemeSpecificPart
    override fun getEncodedSchemeSpecificPart() = parsed.rawSchemeSpecificPart
    override fun getAuthority() = parsed.authority
    override fun getEncodedAuthority() = parsed.rawAuthority
    override fun getUserInfo() = parsed.userInfo
    override fun getEncodedUserInfo() = parsed.rawUserInfo
    override fun getHost() = parsed.host
    override fun getPort() = parsed.port
    override fun getPath() = parsed.path
    override fun getEncodedPath() = parsed.rawPath
    override fun getQuery() = parsed.query
    override fun getEncodedQuery() = parsed.rawQuery
    override fun getFragment() = parsed.fragment
    override fun getEncodedFragment() = parsed.rawFragment
    override fun getPathSegments() = parsed.path.orEmpty().split('/').filter(String::isNotEmpty)
    override fun getLastPathSegment() = getPathSegments().lastOrNull()
    override fun getQueryParameter(key: String) = query[key]
    override fun toString() = raw
    override fun buildUpon(): Uri.Builder = throw UnsupportedOperationException()
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = throw UnsupportedOperationException()
}
