package com.simplexray.an.feature.apps

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.support.HostCoreVersionRead
import com.simplexray.an.ui.navigation.AppNavHost
import com.simplexray.an.ui.theme.SimpleXrayTheme
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [HostCoreVersionRead::class])
@OptIn(ExperimentalTestApi::class)
class AppTabFrameTest {
    @get:Rule
    val compose =
        createAndroidComposeRule<ComponentActivity>(
            effectContext =
                StandardTestDispatcher() +
                    object : MotionDurationScale {
                        override val scaleFactor = 0f
                    }
        )

    private fun launchApp() {
        compose.setContent {
            SimpleXrayTheme(dark = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val model: MainViewModel = viewModel()
                    AppNavHost(model)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun settingsDoNotComposeSectionsFarBelowTheViewport() {
        launchApp()
        compose.onNode(hasText("Настройки") and isSelectable()).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("VPN, IPv6 и проверка сети").assertIsDisplayed()
        compose.onNodeWithText("О приложении").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToIndex(3)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("О приложении").assertIsDisplayed()
        compose.onNodeWithText("Резервная копия").assertDoesNotExist()
        compose.onNodeWithText("Восстановить").assertDoesNotExist()
    }

    @Test
    fun connectionSettingsHaveNoMtuControl() {
        launchApp()
        compose.onNode(hasText("Настройки") and isSelectable()).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Подключение").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Режим работы").assertIsDisplayed()
        compose.onNodeWithContentDescription("MTU").assertDoesNotExist()
        compose.onNodeWithText("MTU").assertDoesNotExist()
    }

    @Test
    fun realApplicationScreensFollowTheirSelectedTabOnTheFirstFrame() {
        launchApp()
        compose.mainClock.autoAdvance = false
        repeat(2) {
            listOf(
                    "Серверы" to hasContentDescription("Поиск серверов"),
                    "Подписки" to hasText("Подписок пока нет.\nДобавьте подписку."),
                    "Настройки" to hasText("VPN, IPv6 и проверка сети"),
                    "Главная" to hasText("Добро пожаловать\nв SimpleXray"),
                )
                .forEach { (label, content) ->
                    compose.onNode(hasText(label) and isSelectable()).performClick()
                    compose.mainClock.advanceTimeByFrame()
                    compose.onNode(hasText(label) and isSelectable()).assertIsSelected()
                    compose.onNode(content).assertIsDisplayed()
                }
        }
    }
}
