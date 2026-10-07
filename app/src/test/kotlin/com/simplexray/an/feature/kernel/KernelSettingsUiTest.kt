package com.simplexray.an.feature.kernel

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.simplexray.an.feature.kernel.model.KernelSettings
import com.simplexray.an.feature.kernel.model.ServerDomainStrategy
import com.simplexray.an.feature.kernel.model.SniffProtocol
import com.simplexray.an.feature.kernel.model.TcpCongestion
import com.simplexray.an.feature.kernel.model.Udp443Mode
import com.simplexray.an.feature.kernel.ui.KernelSettingsSection
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.ui.theme.SimpleXrayTheme
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
@OptIn(ExperimentalTestApi::class)
class KernelSettingsUiTest {
    @get:Rule
    val compose =
        createAndroidComposeRule<ComponentActivity>(
            effectContext =
                StandardTestDispatcher() +
                    object : MotionDurationScale {
                        override val scaleFactor = 0f
                    }
        )

    private fun launchSettings(): Preferences {
        val prefs = Preferences(compose.activity)
        compose.setContent {
            SimpleXrayTheme(dark = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    KernelSettingsSection(prefs)
                }
            }
        }
        return prefs
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun edit(label: String) {
        compose.onNodeWithContentDescription(label).performSemanticsAction(
            SemanticsActions.OnClick
        ) {
            it()
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun input(label: String, value: String) {
        compose
            .onNode(hasContentDescription(label) and hasSetTextAction())
            .performTextReplacement(value)
    }

    @Test
    fun sniffingRouteOnlyAndProtocolsPersistWithoutRestart() {
        val prefs = launchSettings()
        prefs.enable = true
        compose.onNodeWithText("Sniffing").assertIsOn()
        compose.onNodeWithText("Только для маршрутизации").assertIsOn()
        click("Только для маршрутизации")
        assertFalse(
            KernelSettings.decode(Preferences(compose.activity).kernelSettingsJson)
                .sniffingRouteOnly
        )
        edit("Протоколы")
        click("TLS")
        assertEquals(
            setOf(SniffProtocol.HTTP, SniffProtocol.QUIC),
            KernelSettings.decode(prefs.kernelSettingsJson).sniffingProtocols,
        )
        click("Закрыть")
        click("Sniffing")
        assertFalse(KernelSettings.decode(prefs.kernelSettingsJson).sniffingEnabled)
        assertTrue(prefs.enable)
        assertNull(shadowOf(compose.activity.application).nextStartedService)
    }

    @Test
    fun muxAndSocketChoicesSaveIndividually() {
        val prefs = launchSettings()
        click("Mux / XUDP")
        compose.onNodeWithText("Мультиплексирование").assertIsOff()
        click("Мультиплексирование")
        edit("Mux · TCP concurrency")
        input("Mux · TCP concurrency", "-1")
        click("Сохранить")
        edit("UDP / 443")
        click("Разрешать")
        click("Sockopt · параметры сокета")
        edit("Разрешение адреса сервера")
        click("UseIPv6v4")
        click("TCP Fast Open")
        edit("Алгоритм TCP")
        click("BBR")
        val saved = KernelSettings.decode(prefs.kernelSettingsJson)
        assertTrue(saved.muxEnabled)
        assertEquals(-1, saved.muxConcurrency)
        assertEquals(Udp443Mode.ALLOW, saved.udp443)
        assertEquals(ServerDomainStrategy.USE_IPV6V4, saved.serverDomainStrategy)
        assertTrue(saved.tcpFastOpen)
        assertEquals(TcpCongestion.BBR, saved.tcpCongestion)
    }

    @Test
    fun invalidNumericInputKeepsSavedValueAndDialogOpen() {
        val prefs = launchSettings()
        click("Mux / XUDP")
        edit("Mux · TCP concurrency")
        val original = prefs.kernelSettingsJson
        for (invalid in listOf("129", "1.5", "2147483648", "")) {
            input("Mux · TCP concurrency", invalid)
            click("Сохранить")
            assertEquals(original, prefs.kernelSettingsJson)
            compose
                .onNode(hasContentDescription("Mux · TCP concurrency") and hasSetTextAction())
                .assertExists()
        }
        input("Mux · TCP concurrency", "128")
        click("Сохранить")
        assertEquals(128, KernelSettings.decode(prefs.kernelSettingsJson).muxConcurrency)
    }

    @Test
    fun blankPolicyRestoresDefaultWhileExplicitZeroPersists() {
        val prefs = launchSettings()
        click("Policy · лимиты соединения")
        edit("Uplink only, с")
        input("Uplink only, с", "0")
        click("Сохранить")
        assertEquals(0, KernelSettings.decode(prefs.kernelSettingsJson).uplinkOnly)
        edit("Handshake, с")
        input("Handshake, с", "0")
        click("Сохранить")
        assertNull(KernelSettings.decode(prefs.kernelSettingsJson).handshake)
        input("Handshake, с", "12")
        click("Сохранить")
        assertEquals(12, KernelSettings.decode(prefs.kernelSettingsJson).handshake)
        edit("Handshake, с")
        input("Handshake, с", "")
        click("Сохранить")
        assertNull(KernelSettings.decode(prefs.kernelSettingsJson).handshake)
        edit("Uplink only, с")
        click("Сбросить поле")
        click("Сохранить")
        assertNull(KernelSettings.decode(prefs.kernelSettingsJson).uplinkOnly)
    }

    @Test
    fun numericControlsPersistIntoTheirOwnFields() {
        val prefs = launchSettings()
        click("Mux / XUDP")
        edit("XUDP concurrency")
        input("XUDP concurrency", "1024")
        click("Сохранить")
        click("Sockopt · параметры сокета")
        for ((label, value) in
            listOf("TCP keep-alive, с" to "3600", "TCP user timeout, мс" to "600000")) {
            edit(label)
            input(label, value)
            click("Сохранить")
        }
        click("Policy · лимиты соединения")
        for ((label, value) in
            listOf(
                "Простой соединения, с" to "86400",
                "Downlink only, с" to "0",
                "Буфер, КиБ" to "0",
            )) {
            edit(label)
            input(label, value)
            click("Сохранить")
        }
        val saved = KernelSettings.decode(Preferences(compose.activity).kernelSettingsJson)
        assertEquals(1024, saved.xudpConcurrency)
        assertEquals(3600, saved.tcpKeepAliveInterval)
        assertEquals(600000, saved.tcpUserTimeout)
        assertEquals(86400, saved.connectionIdle)
        assertEquals(0, saved.downlinkOnly)
        assertEquals(0, saved.bufferSize)
    }

    @Test
    fun corruptStorageHasResetRecovery() {
        val prefs = Preferences(compose.activity)
        prefs.kernelSettingsJson = "broken"
        launchSettings()
        click("Сбросить настройки ядра")
        assertNull(prefs.kernelSettingsJson)
        compose.onNodeWithText("Sniffing").assertIsOn()
        compose.onNodeWithText("Только для маршрутизации").assertIsOn()
        click("Mux / XUDP")
        compose.onNodeWithText("Мультиплексирование").assertIsOff()
    }
}
