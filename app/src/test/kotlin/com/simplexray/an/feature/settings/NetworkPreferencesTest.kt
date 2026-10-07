package com.simplexray.an.feature.settings

import android.app.Application
import androidx.preference.PreferenceManager
import com.simplexray.an.core.config.AppConfig
import com.simplexray.an.core.files.FileManager
import com.simplexray.an.feature.settings.model.InboundSettings
import com.simplexray.an.feature.settings.state.SettingsController
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.service.runtime.VpnRuntimeSettings
import com.simplexray.an.service.runtime.VpnTunnelConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NetworkPreferencesTest {
    @Test
    fun fixedMtuIgnoresLegacyPreferenceAndAllowsIpv6() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = Preferences(app)
        val controller = SettingsController(app, prefs, FileManager(app, prefs), {}, {})
        for (legacy in listOf(576, 1400, 9000)) {
            prefs.ipv6 = false
            PreferenceManager.getDefaultSharedPreferences(app)
                .edit()
                .putInt("TunnelMtu", legacy)
                .apply()
            controller.setIpv6Enabled(true)
            assertTrue(prefs.ipv6)
            val runtime = VpnRuntimeSettings(prefs, AppConfig(prefs))
            assertEquals(8500, runtime.tunnelMtu)
            assertTrue(VpnTunnelConfig.configuration(runtime).contains("mtu: 8500"))
        }
    }

    @Test
    fun legacySocksOnDefaultHttpPortGetsFreeHttpDefaultWithoutChangingSavedPorts() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        prefs.socksPort = 10809
        assertEquals(10809, prefs.socksPort)
        assertEquals(10810, prefs.httpPort)
        InboundSettings.fromPreferences(prefs).validate()
        prefs.httpPort = 10809
        assertEquals(10809, prefs.httpPort)
        assertThrows(IllegalArgumentException::class.java) {
            InboundSettings.fromPreferences(prefs).validate()
        }
        prefs.httpPort = 18080
        assertEquals(18080, Preferences(RuntimeEnvironment.getApplication()).httpPort)
    }

    @Test
    fun defaultsAndRoundTripPreserveNetworkSettings() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        assertEquals(10809, prefs.httpPort)
        assertTrue(prefs.socksUdpEnabled)
        assertFalse(prefs.allowLanAccess)
        prefs.httpPort = 18080
        prefs.socksUdpEnabled = false
        prefs.allowLanAccess = true
        val current = Preferences(RuntimeEnvironment.getApplication())
        assertEquals(18080, current.httpPort)
        assertFalse(current.socksUdpEnabled)
        assertTrue(current.allowLanAccess)
    }

    @Test
    fun controllerRejectsInvalidEditsAndDisablingLanRestoresLoopback() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = Preferences(app)
        val controller = SettingsController(app, prefs, FileManager(app, prefs), {}, {})
        assertFalse(controller.updateSocksAddress("0.0.0.0"))
        controller.setAllowLanAccess(true)
        assertTrue(controller.updateSocksAddress("0.0.0.0"))
        controller.setAllowLanAccess(false)
        assertEquals("127.0.0.1", prefs.socksAddress)
        assertFalse(controller.updateHttpPort("10808"))
        assertEquals(10809, prefs.httpPort)
        controller.setIpv6Enabled(true)
        assertTrue(prefs.ipv6)
        assertTrue(controller.settingsState.value.switches.ipv6Enabled)
    }

    @Test
    fun legacyNonLoopbackBindRetainsAccessUntilExplicitlyDisabled() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        prefs.socksAddress = "0.0.0.0"
        assertTrue(prefs.allowLanAccess)
        prefs.allowLanAccess = false
        assertFalse(Preferences(RuntimeEnvironment.getApplication()).allowLanAccess)
    }
}
