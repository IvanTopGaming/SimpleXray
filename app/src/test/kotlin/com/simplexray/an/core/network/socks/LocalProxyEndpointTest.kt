package com.simplexray.an.core.network.socks

import android.app.Application
import com.simplexray.an.prefs.Preferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalProxyEndpointTest {
    @Test
    fun capturedEndpointSurvivesEditsToPendingSettings() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        prefs.activeProxySettingsJson =
            LocalProxyEndpoint("192.168.1.8", 18080, "active", "active-pass").encode()
        prefs.socksAddress = "127.0.0.1"
        prefs.socksPort = 19090
        prefs.socksUsername = "draft"
        prefs.socksPassword = "draft-pass"
        val endpoint = LocalProxyEndpoint.active(prefs)
        assertEquals("192.168.1.8", endpoint.host)
        assertEquals(18080, endpoint.port)
        assertEquals("active", endpoint.username)
        assertEquals("active-pass", endpoint.password)
    }

    @Test
    fun legacyFallbackMapsWildcardAndHonorsLanGate() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        assertNull(prefs.activeProxySettingsJson)
        prefs.allowLanAccess = true
        prefs.socksAddress = "0.0.0.0"
        assertEquals("127.0.0.1", LocalProxyEndpoint.active(prefs).host)
        prefs.socksAddress = "::"
        assertEquals("::1", LocalProxyEndpoint.active(prefs).host)
        prefs.socksAddress = "192.168.1.8"
        assertEquals("192.168.1.8", LocalProxyEndpoint.active(prefs).host)
        prefs.allowLanAccess = false
        assertEquals("127.0.0.1", LocalProxyEndpoint.active(prefs).host)
    }

    @Test
    fun authenticationMatchesOnlyTheActualSocksEndpoint() {
        val endpoint = LocalProxyEndpoint("127.0.0.1", 10808, "user", "secret")
        assertNull(endpoint.authentication("127.0.0.2", 10808, "SOCKS5"))
        assertNull(endpoint.authentication("127.0.0.1", 10809, "SOCKS5"))
        assertNull(endpoint.authentication("127.0.0.1", 10808, "HTTP"))
        val authentication = endpoint.authentication("127.0.0.1", 10808, "SOCKS5")!!
        assertEquals("user", authentication.userName)
        assertArrayEquals("secret".toCharArray(), authentication.password)
    }
}
