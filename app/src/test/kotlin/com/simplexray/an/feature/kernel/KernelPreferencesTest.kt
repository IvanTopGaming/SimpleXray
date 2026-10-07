package com.simplexray.an.feature.kernel

import android.app.Application
import com.google.gson.JsonParser
import com.simplexray.an.core.config.AppConfig
import com.simplexray.an.feature.dns.model.DnsSettings
import com.simplexray.an.feature.kernel.model.KernelSettings
import com.simplexray.an.prefs.Preferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class KernelPreferencesTest {
    private val source =
        """{"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":12345}]}}]}"""

    @Test
    fun missingPreferencesUseApprovedSniffingAndMuxDefaults() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        assertNull(prefs.kernelSettingsJson)
        val root = JsonParser.parseString(AppConfig(prefs).build(source)).asJsonObject
        val sniffing = root.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonObject("sniffing")
        assertTrue(sniffing["enabled"].asBoolean)
        assertTrue(sniffing["routeOnly"].asBoolean)
        assertTrue(
            sniffing
                .getAsJsonArray("destOverride")
                .map { it.asString }
                .containsAll(listOf("http", "tls", "quic"))
        )
        val proxy =
            root
                .getAsJsonArray("outbounds")
                .first { it.asJsonObject["tag"].asString == "proxy" }
                .asJsonObject
        assertFalse(proxy.getAsJsonObject("mux")["enabled"].asBoolean)
    }

    @Test
    fun connectionSnapshotKeepsKernelSettingsAcrossPreferenceChanges() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        prefs.dnsSettingsJson = DnsSettings(fakeIpEnabled = false).encode()
        prefs.kernelSettingsJson =
            KernelSettings(sniffingRouteOnly = false, handshake = 12, uplinkOnly = 0).encode()
        val snapshot = AppConfig(prefs)
        prefs.kernelSettingsJson = KernelSettings(sniffingEnabled = false, handshake = 34).encode()
        val original = JsonParser.parseString(snapshot.build(source)).asJsonObject
        val originalSniffing =
            original.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonObject("sniffing")
        assertTrue(originalSniffing["enabled"].asBoolean)
        assertFalse(originalSniffing["routeOnly"].asBoolean)
        assertEquals(
            12,
            original
                .getAsJsonObject("policy")
                .getAsJsonObject("levels")
                .getAsJsonObject("0")["handshake"]
                .asInt,
        )
        assertEquals(
            0,
            original
                .getAsJsonObject("policy")
                .getAsJsonObject("levels")
                .getAsJsonObject("0")["uplinkOnly"]
                .asInt,
        )
        val current =
            JsonParser.parseString(
                    AppConfig(Preferences(RuntimeEnvironment.getApplication())).build(source)
                )
                .asJsonObject
        assertFalse(
            current
                .getAsJsonArray("inbounds")[0]
                .asJsonObject
                .getAsJsonObject("sniffing")["enabled"]
                .asBoolean
        )
        assertEquals(
            34,
            current
                .getAsJsonObject("policy")
                .getAsJsonObject("levels")
                .getAsJsonObject("0")["handshake"]
                .asInt,
        )
    }

    @Test
    fun corruptSnapshotFailsWithoutOverwritingStoredDataAndCanBeCleared() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        prefs.kernelSettingsJson = "broken"
        assertThrows(IllegalArgumentException::class.java) { AppConfig(prefs) }
        assertEquals("broken", prefs.kernelSettingsJson)
        prefs.kernelSettingsJson = null
        assertEquals(
            KernelSettings(),
            KernelSettings.decode(
                Preferences(RuntimeEnvironment.getApplication()).kernelSettingsJson
            ),
        )
    }
}
