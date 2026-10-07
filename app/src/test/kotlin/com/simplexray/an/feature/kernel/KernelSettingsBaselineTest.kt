package com.simplexray.an.feature.kernel

import android.app.Application
import com.google.gson.JsonParser
import com.simplexray.an.core.config.AppConfig
import com.simplexray.an.core.config.ConfigUtils
import com.simplexray.an.prefs.Preferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class KernelSettingsBaselineTest {
    @Test
    fun defaultClientSettingsDisableImportedPrimaryMux() {
        val source =
            """{"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":12345}]},"mux":{"enabled":true,"concurrency":8}}]}"""
        val config =
            JsonParser.parseString(
                    AppConfig(Preferences(RuntimeEnvironment.getApplication())).build(source)
                )
                .asJsonObject
        val proxy =
            config
                .getAsJsonArray("outbounds")
                .first { it.asJsonObject["tag"].asString == "proxy" }
                .asJsonObject
        assertFalse(proxy.getAsJsonObject("mux")["enabled"].asBoolean)
    }

    @Test
    fun statisticsInjectionPreservesSessionPolicyLevels() {
        val source =
            """{"policy":{"levels":{"0":{"handshake":12,"connIdle":90,"uplinkOnly":0,"downlinkOnly":3,"bufferSize":8},"7":{"handshake":12}},"system":{"statsInboundUplink":true}}}"""
        val config =
            JsonParser.parseString(ConfigUtils.injectStatsService("127.0.0.1", 12000, source))
                .asJsonObject
        val before = JsonParser.parseString(source).asJsonObject["policy"].asJsonObject
        val after = config.getAsJsonObject("policy")
        assertEquals(before["levels"], after["levels"])
        assertTrue(after["system"].asJsonObject["statsInboundUplink"].asBoolean)
        assertTrue(after["system"].asJsonObject["statsOutboundUplink"].asBoolean)
        assertTrue(after["system"].asJsonObject["statsOutboundDownlink"].asBoolean)
    }
}
