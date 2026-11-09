package app.onesvpn

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class HttpProbeSessionTest {
    private class FakeHttp(url: URL, private val code: Int = 204, private val fail: Boolean = false) :
        HttpURLConnection(url) {
        var disconnects = 0
        override fun connect() = Unit
        override fun disconnect() { disconnects++ }
        override fun usingProxy() = false
        override fun getResponseCode(): Int {
            if (fail) throw IOException("simulated network failure")
            return code
        }
    }

    @Test fun successfulVpnBoundProbeReleasesConnection() {
        val opened = mutableListOf<FakeHttp>()
        val session = HttpProbeSession()
        assertTrue(session.check({ url -> FakeHttp(url).also { opened.add(it) } }))
        assertEquals(1, opened.size)
        assertEquals(1, opened.single().disconnects)
        session.close()
    }

    @Test fun failedEndpointFallsBackWithoutUsingAnyImplicitNetwork() {
        val opened = mutableListOf<FakeHttp>()
        val session = HttpProbeSession()
        assertTrue(session.check({ url ->
            FakeHttp(url, if (opened.isEmpty()) 503 else 204).also { opened.add(it) }
        }))
        assertEquals(2, opened.size)
        assertTrue(opened.all { it.disconnects == 1 })
        session.close()
    }

    @Test fun thrownHttpRequestClosesConnectionAndReportsFailure() {
        val opened = mutableListOf<FakeHttp>()
        var failureCount = 0
        val session = HttpProbeSession()
        assertFalse(session.check(
            openConnection = { url -> FakeHttp(url, fail = true).also { opened.add(it) } },
            onFailure = { failureCount++ }
        ))
        assertEquals(2, failureCount)
        assertEquals(2, opened.size)
        assertTrue(opened.all { it.disconnects == 1 })
        session.close()
    }

    @Test fun cancellationDuringConnectionCreationReleasesLateConnection() {
        val session = HttpProbeSession()
        lateinit var late: FakeHttp
        assertFalse(session.check({ url ->
            late = FakeHttp(url)
            session.close()
            late
        }))
        assertEquals(1, late.disconnects)
    }

    @Test fun closedProbeMustNotOpenAnyNewConnections() {
        val session = HttpProbeSession()
        session.close()
        var calls = 0
        assertFalse(session.check({ url ->
            calls++
            FakeHttp(url)
        }))
        assertEquals(0, calls)
    }
}
