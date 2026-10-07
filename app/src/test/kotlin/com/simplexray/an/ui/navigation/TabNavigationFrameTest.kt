package com.simplexray.an.ui.navigation

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.simplexray.an.ui.scaffold.AppScaffold
import com.simplexray.an.ui.scaffold.navigateToRoute
import com.simplexray.an.ui.theme.SimpleXrayTheme
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalTestApi::class)
class TabNavigationFrameTest {
    @get:Rule
    val compose =
        createAndroidComposeRule<ComponentActivity>(
            effectContext =
                StandardTestDispatcher() +
                    object : MotionDurationScale {
                        override val scaleFactor = 0f
                    }
        )
    private lateinit var controller: NavHostController
    private var chromeCompositions = 0
    private val tabs =
        listOf(
            ROUTE_STATS to "Главная",
            ROUTE_CONFIG to "Серверы",
            ROUTE_SUBSCRIPTIONS to "Подписки",
            ROUTE_SETTINGS to "Настройки",
        )

    private fun launch(): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SimpleXrayTheme(dark = false) {
                controller = rememberNavController()
                ImmediateNavHost(
                    controller,
                    ROUTE_STATS,
                    tabs.map { it.first } + "settings-detail/{section}",
                    chrome = { entry, body ->
                        DisposableEffect(Unit) {
                            chromeCompositions++
                            onDispose {}
                        }
                        AppScaffold(
                            controller,
                            entry,
                            remember { SnackbarHostState() },
                            {},
                            {},
                            {},
                        ) { padding ->
                            Column(Modifier.fillMaxSize().padding(padding)) { body() }
                        }
                    },
                ) { entry ->
                    Text("content-${entry.destination.route}")
                    if (entry.destination.route == ROUTE_CONFIG) {
                        var query by rememberSaveable { mutableStateOf("") }
                        BasicTextField(query, { query = it }, Modifier.testTag("query"))
                        LazyColumn(Modifier.testTag("servers"), state = rememberLazyListState()) {
                            items(80) { Text("server-$it") }
                        }
                    }
                    if (entry.destination.route == "settings-detail/{section}") {
                        Text("section-${entry.arguments?.getString("section")}")
                    }
                }
            }
        }
        compose.waitForIdle()
        return restoration
    }

    @Test
    fun selectedTabAndContentChangeInTheSameFrame() {
        launch()
        compose.mainClock.autoAdvance = false
        (tabs.drop(1) + tabs.take(1) + tabs.drop(1).reversed()).forEach { (route, label) ->
            compose.onNode(hasText(label) and isSelectable()).performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.onNode(hasText(label) and isSelectable()).assertIsSelected()
            compose.onNodeWithText("content-$route").assertExists()
            tabs
                .filter { it.first != route }
                .forEach { (other, _) ->
                    compose.onNodeWithText("content-$other").assertDoesNotExist()
                }
        }
        assertEquals("Shared chrome must not be recreated on each tab", 1, chromeCompositions)
    }

    @Test
    fun queryAndScrollSurviveSwitchingTabsAndSavedStateRestoration() {
        val restoration = launch()
        compose.runOnIdle { navigateToRoute(controller, ROUTE_CONFIG) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("query").performTextInput("Amsterdam")
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("servers").performScrollToIndex(50)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("server-50").assertIsDisplayed()
        compose.runOnIdle { navigateToRoute(controller, ROUTE_SUBSCRIPTIONS) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { navigateToRoute(controller, ROUTE_CONFIG) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("query").assertTextEquals("Amsterdam")
        compose.onNodeWithText("server-50").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("query").assertTextEquals("Amsterdam")
        compose.onNodeWithText("server-50").assertIsDisplayed()
    }

    @Test
    fun settingsArgumentsAndBackNavigationKeepOwnersCorrect() {
        launch()
        compose.runOnIdle { navigateToRoute(controller, ROUTE_SETTINGS) }
        compose.mainClock.advanceTimeByFrame()
        val settingsEntry = compose.runOnIdle { controller.currentBackStackEntry!! }
        compose.runOnIdle {
            controller.navigate("settings-detail/" + Uri.encode("Ядро и конфигурация"))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("section-Ядро и конфигурация").assertExists()
        val detail = compose.runOnIdle { controller.currentBackStackEntry!! }
        compose.runOnIdle {
            assertEquals(Lifecycle.State.RESUMED, detail.lifecycle.currentState)
            assertEquals(Lifecycle.State.CREATED, settingsEntry.lifecycle.currentState)
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("content-$ROUTE_SETTINGS").assertExists()
        compose.runOnIdle {
            assertEquals(Lifecycle.State.DESTROYED, detail.lifecycle.currentState)
            assertEquals(Lifecycle.State.RESUMED, settingsEntry.lifecycle.currentState)
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("content-$ROUTE_STATS").assertExists()
    }

    @Test
    fun rapidNavigationAndReselectionSettleOnTheLastTab() {
        launch()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            repeat(3) {
                tabs.forEach { (route, _) -> navigateToRoute(controller, route) }
            }
            navigateToRoute(controller, ROUTE_SETTINGS)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("content-$ROUTE_SETTINGS").assertExists()
        compose.onNode(hasText("Настройки") and isSelectable()).assertIsSelected()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("content-$ROUTE_STATS").assertExists()
    }

    @Test
    fun pushAndPopBeforeTheNextFrameFinishBothLifecycles() {
        launch()
        compose.mainClock.advanceTimeBy(64)
        compose.mainClock.autoAdvance = false
        val home = compose.runOnIdle { controller.currentBackStackEntry!! }
        val popped = compose.runOnIdle {
            controller.navigate(ROUTE_CONFIG)
            val entry = controller.currentBackStackEntry!!
            controller.popBackStack()
            entry
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("content-$ROUTE_STATS").assertExists()
        compose.runOnIdle {
            assertEquals(Lifecycle.State.RESUMED, home.lifecycle.currentState)
            assertEquals(Lifecycle.State.DESTROYED, popped.lifecycle.currentState)
        }
    }
}
