package com.simplexray.an.feature.dns

import android.app.Application
import com.google.gson.JsonParser
import com.simplexray.an.core.config.AppConfig
import com.simplexray.an.feature.dns.model.DnsSettings
import com.simplexray.an.prefs.Preferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DnsPreferencesTest {
    private val source =
        """{"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":12345}]}}]}"""

    @Test
    fun absentPreferencesUseExistingInterfaceResolverWithFakeIpEnabled() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        prefs.dnsIpv4 = "9.9.9.9"
        assertNull(prefs.dnsSettingsJson)
        val settings = DnsSettings.decode(prefs.dnsSettingsJson, prefs.dnsIpv4)
        assertEquals("9.9.9.9", settings.primaryDns)
        assertTrue(settings.fakeIpEnabled)
        val root = JsonParser.parseString(AppConfig(prefs).build(source)).asJsonObject
        assertEquals(
            "198.19.0.0/16",
            root.getAsJsonArray("fakedns")[0].asJsonObject["ipPool"].asString,
        )
    }

    @Test
    fun persistedResolverIsIndependentOfAndroidInterfaceDns() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        val settings =
            DnsSettings(primaryDns = "https://dns.example/dns-query", primaryBootstrap = "1.2.3.4")
        prefs.dnsSettingsJson = settings.encode()
        prefs.dnsIpv4 = "9.9.9.9"
        val reloaded = Preferences(RuntimeEnvironment.getApplication())
        assertEquals(settings, DnsSettings.decode(reloaded.dnsSettingsJson, reloaded.dnsIpv4))
        assertEquals("9.9.9.9", reloaded.dnsIpv4)
    }

    @Test
    fun runtimeConfigRetainsImmutableSettingsAcrossLaterPreferenceChanges() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        prefs.dnsSettingsJson = DnsSettings(primaryDns = "9.9.9.9").encode()
        prefs.ipv6 = false
        val snapshot = AppConfig(prefs)
        prefs.dnsSettingsJson = DnsSettings(primaryDns = "1.1.1.1", fakeIpEnabled = false).encode()
        prefs.ipv6 = true
        val old = JsonParser.parseString(snapshot.build(source)).asJsonObject
        assertEquals(1, old.getAsJsonArray("fakedns").size())
        assertEquals(
            "9.9.9.9",
            old.getAsJsonObject("dns")
                .getAsJsonArray("servers")[1]
                .asJsonObject["address"]
                .asString,
        )
        val updated = JsonParser.parseString(AppConfig(prefs).build(source)).asJsonObject
        assertFalse(updated.has("fakedns"))
        assertEquals(
            "1.1.1.1",
            updated
                .getAsJsonObject("dns")
                .getAsJsonArray("servers")[0]
                .asJsonObject["address"]
                .asString,
        )
    }

    @Test
    fun corruptSnapshotFailsWithoutOverwritingStoredData() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        prefs.dnsSettingsJson = "broken"
        assertThrows(IllegalArgumentException::class.java) { AppConfig(prefs) }
        assertEquals("broken", prefs.dnsSettingsJson)
    }
}
