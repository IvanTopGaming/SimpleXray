package com.simplexray.an.service.runtime

import android.app.Application
import com.google.gson.JsonParser
import com.simplexray.an.core.config.AppConfig
import com.simplexray.an.prefs.Preferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VpnHttpProxyTest {
    @Test
    fun authenticatedHttpListenerIsNotAdvertisedToAndroidApps() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        prefs.socksUsername = "user"
        prefs.socksPassword = "secret"
        val config = AppConfig(prefs)
        val source =
            """{"protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":12345}]}}"""
        val http =
            JsonParser.parseString(config.build(source))
                .asJsonObject
                .getAsJsonArray("inbounds")
                .single { it.asJsonObject["protocol"].asString == "http" }
                .asJsonObject
        assertEquals(
            "user",
            http
                .getAsJsonObject("settings")
                .getAsJsonArray("accounts")[0]
                .asJsonObject["user"]
                .asString,
        )
        assertFalse(VpnRuntimeSettings(prefs, config).advertiseHttpProxy)
    }

    @Test
    fun unauthenticatedHttpListenerAdvertisesItsSeparatePort() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        prefs.httpPort = 18080
        val runtime = VpnRuntimeSettings(prefs, AppConfig(prefs))
        assertTrue(runtime.advertiseHttpProxy)
        assertEquals("127.0.0.1", runtime.socksAddress)
        assertEquals(18080, runtime.httpPort)
        prefs.httpProxyEnabled = false
        assertFalse(VpnRuntimeSettings(prefs, AppConfig(prefs)).advertiseHttpProxy)
    }
}
