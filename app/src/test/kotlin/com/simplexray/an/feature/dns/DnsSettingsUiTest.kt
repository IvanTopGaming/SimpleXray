package com.simplexray.an.feature.dns

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
import com.simplexray.an.feature.dns.model.DnsSettings
import com.simplexray.an.feature.dns.ui.DnsSettingsSection
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
class DnsSettingsUiTest {
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
                Column(Modifier.verticalScroll(rememberScrollState())) { DnsSettingsSection(prefs) }
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

    @Test
    fun fakeIpDefaultsOnAndChangesPersistWithoutRestart() {
        val prefs = launchSettings()
        prefs.enable = true
        compose.onNodeWithText("FakeIP").assertIsOn()
        click("FakeIP")
        assertFalse(DnsSettings.decode(Preferences(compose.activity).dnsSettingsJson).fakeIpEnabled)
        assertTrue(prefs.enable)
        assertNull(shadowOf(compose.activity.application).nextStartedService)
        click("Кэшировать ответы")
        assertFalse(DnsSettings.decode(prefs.dnsSettingsJson).cacheEnabled)
    }

    @Test
    fun resolverAndBootstrapSaveTogetherWithoutChangingInterfaceDns() {
        val prefs = launchSettings()
        val interfaceDns = prefs.dnsIpv4
        edit("Основной DNS")
        compose
            .onNode(hasContentDescription("Основной DNS") and hasSetTextAction())
            .performTextReplacement("https://dns.example/dns-query")
        compose
            .onNode(hasContentDescription("Bootstrap IP") and hasSetTextAction())
            .performTextReplacement("1.2.3.4")
        click("Сохранить")
        val saved = DnsSettings.decode(prefs.dnsSettingsJson)
        assertEquals("https://dns.example/dns-query", saved.primaryDns)
        assertEquals("1.2.3.4", saved.primaryBootstrap)
        assertEquals(interfaceDns, prefs.dnsIpv4)
        edit("Основной DNS")
        compose
            .onNode(hasContentDescription("Основной DNS") and hasSetTextAction())
            .assertTextEquals("https://dns.example/dns-query")
        click("Отмена")
    }

    @Test
    fun directDnsAndBootstrapCanBeChangedTogether() {
        val prefs = launchSettings()
        edit("DNS напрямую")
        compose
            .onNode(hasContentDescription("DNS напрямую") and hasSetTextAction())
            .performTextReplacement("https://direct.example/dns-query")
        compose
            .onNode(hasContentDescription("Bootstrap IP") and hasSetTextAction())
            .performTextReplacement("77.88.8.8")
        click("Сохранить")
        val saved = DnsSettings.decode(prefs.dnsSettingsJson)
        assertEquals("https://direct.example/dns-query", saved.directDns)
        assertEquals("77.88.8.8", saved.directBootstrap)
        assertEquals(DnsSettings().primaryDns, saved.primaryDns)
        edit("DNS напрямую")
        compose
            .onNode(hasContentDescription("DNS напрямую") and hasSetTextAction())
            .assertTextEquals("https://direct.example/dns-query")
        click("Отмена")
    }

    @Test
    fun invalidBootstrapAndResolverCannotReplaceSavedSettings() {
        val prefs = launchSettings()
        val original = prefs.dnsSettingsJson
        edit("Основной DNS")
        compose
            .onNode(hasContentDescription("Основной DNS") and hasSetTextAction())
            .performTextReplacement("https://dns.example/dns-query")
        compose
            .onNode(hasContentDescription("Bootstrap IP") and hasSetTextAction())
            .performTextReplacement("resolver.example")
        click("Сохранить")
        assertEquals(original, prefs.dnsSettingsJson)
        compose.onNodeWithText("Для домена DoH укажи bootstrap IP сервера").assertExists()
        compose
            .onNode(hasContentDescription("Основной DNS") and hasSetTextAction())
            .performTextReplacement("http://dns.example/dns-query")
        click("Сохранить")
        assertEquals(original, prefs.dnsSettingsJson)
        compose.onNodeWithText("DNS поддерживает IP, UDP и HTTPS").assertExists()
        click("Отмена")
    }

    @Test
    fun corruptSnapshotCanBeResetAndFallbackSavedIndependently() {
        val prefs = Preferences(compose.activity)
        prefs.dnsSettingsJson = "broken"
        prefs.dnsIpv4 = "9.9.9.9"
        launchSettings()
        click("Сбросить DNS")
        assertTrue(DnsSettings.decode(prefs.dnsSettingsJson).fakeIpEnabled)
        assertEquals("9.9.9.9", DnsSettings.decode(prefs.dnsSettingsJson).primaryDns)
        edit("Резервный адрес")
        compose
            .onNode(hasContentDescription("Резервный адрес") and hasSetTextAction())
            .performTextReplacement("8.8.4.4")
        click("Сохранить")
        assertFalse(DnsSettings.decode(prefs.dnsSettingsJson).fallbackEnabled)
        click("Резервный DNS")
        assertTrue(DnsSettings.decode(prefs.dnsSettingsJson).fallbackEnabled)
        assertEquals("8.8.4.4", DnsSettings.decode(prefs.dnsSettingsJson).fallbackDns)
    }
}
