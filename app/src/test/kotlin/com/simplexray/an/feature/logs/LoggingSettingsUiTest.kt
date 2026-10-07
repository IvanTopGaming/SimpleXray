package com.simplexray.an.feature.logs

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
import com.simplexray.an.feature.dashboard.model.CoreStatsState
import com.simplexray.an.feature.dashboard.ui.DashboardSpeeds
import com.simplexray.an.feature.dashboard.ui.DashboardTotals
import com.simplexray.an.feature.logs.model.LogSettings
import com.simplexray.an.feature.logs.ui.LoggingSettingsSection
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
class LoggingSettingsUiTest {
    @get:Rule
    val compose =
        createAndroidComposeRule<ComponentActivity>(
            effectContext =
                StandardTestDispatcher() +
                    object : MotionDurationScale {
                        override val scaleFactor = 0f
                    }
        )

    @Test
    fun switchesAndLevelPersistWithoutChangingActiveSession() {
        val prefs = Preferences(compose.activity)
        prefs.enable = true
        prefs.activeTrafficStatsEnabled = true
        compose.setContent {
            SimpleXrayTheme(dark = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LoggingSettingsSection(prefs)
                }
            }
        }
        fun click(label: String) {
            compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.mainClock.advanceTimeByFrame()
        }
        compose.onNodeWithText("Статистика трафика").assertIsOn()
        click("Журнал подключений")
        click("Журнал DNS")
        click("Скрывать IP в логах")
        click("Статистика трафика")
        compose.onNodeWithContentDescription("Уровень журнала").performSemanticsAction(
            SemanticsActions.OnClick
        ) {
            it()
        }
        compose.mainClock.advanceTimeByFrame()
        click("debug")
        assertEquals(
            LogSettings("debug", true, true, true, false),
            LogSettings.decode(prefs.logSettingsJson),
        )
        assertEquals(prefs.logSettingsJson, Preferences(compose.activity).logSettingsJson)
        assertTrue(prefs.activeTrafficStatsEnabled)
        assertTrue(prefs.enable)
        assertNull(shadowOf(compose.activity.application).nextStartedService)
    }

    @Test
    fun dashboardShowsDisabledTrafficAndKeepsUptime() {
        compose.setContent {
            SimpleXrayTheme(dark = false) {
                Column {
                    DashboardSpeeds(true, 5000, 9000, Modifier, trafficStatsEnabled = false)
                    DashboardTotals(
                        true,
                        CoreStatsState(
                            uplink = 5000,
                            downlink = 9000,
                            uptime = 123,
                            trafficStatsEnabled = false,
                        ),
                        Modifier,
                    )
                }
            }
        }
        compose.onAllNodesWithText("Выключено", substring = true).assertCountEquals(4)
        compose.onNodeWithText("02:03").assertExists()
        compose.onNodeWithText("0 КБ/с").assertDoesNotExist()
    }
}
