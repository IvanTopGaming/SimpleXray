package com.simplexray.an.feature.subscriptions

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.ui.RoutingProfileTab
import com.simplexray.an.feature.routing.ui.RoutingProfileTabs
import com.simplexray.an.feature.routing.ui.SubscriptionRoutingTab
import com.simplexray.an.feature.subscriptions.model.Subscription
import com.simplexray.an.feature.subscriptions.routing.SubscriptionRouting
import com.simplexray.an.feature.subscriptions.routing.SubscriptionRoutingBlock
import com.simplexray.an.feature.subscriptions.routing.SubscriptionRoutingView
import com.simplexray.an.feature.subscriptions.ui.AddSubscriptionDialog
import com.simplexray.an.feature.subscriptions.ui.SubscriptionCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h900dp", application = Application::class)
class SubscriptionRoutingUiTest {
    @get:Rule val compose = createComposeRule()

    private val sub = Subscription("sub-a", "Feed", "https://sub.example/a", 1L, listOf("Feed - DE.json"))

    private val view =
        SubscriptionRoutingView(
            SubscriptionRouting(
                listOf(
                    SubscriptionRoutingBlock(RouteTarget.DIRECT, "geoip:ru"),
                    SubscriptionRoutingBlock(RouteTarget.PROXY, "suffix:youtube.com", true, "DE"),
                ),
                geositeUrl = "https://provider.example/geosite.dat",
            ),
            listOf("DE"),
        )

    @Test
    fun subscriptionTabShowsRulesDatabasesAndWarnings() {
        compose.setContent { MaterialTheme { SubscriptionRoutingTab(sub, view) } }
        compose.onNodeWithText("Через сервер «DE»").assertExists()
        compose.onNodeWithText("suffix:youtube.com").assertExists()
        compose.onNodeWithText("https://provider.example/geosite.dat").assertExists()
        compose.onNodeWithText("Глобальная база").assertExists()
        compose.onNodeWithText("Сервер «DE» не найден — правила идут через активный сервер.").assertExists()
    }

    @Test
    fun disabledSubscriptionTabExplainsWhereToEnable() {
        compose.setContent { MaterialTheme { SubscriptionRoutingTab(sub.copy(routingDisabled = true), view) } }
        compose.onNodeWithText("Приём выключен в настройках подписки — эти правила сейчас не применяются.").assertExists()
        compose.onNodeWithText("Сервер «DE» не найден — правила идут через активный сервер.").assertDoesNotExist()
    }

    @Test
    fun tabsSwitchBetweenGlobalAndSubscription() {
        var selected by mutableStateOf<String?>(null)
        compose.setContent {
            MaterialTheme {
                RoutingProfileTabs(listOf(RoutingProfileTab(null, "Глобальный"), RoutingProfileTab("sub-a", "Feed")), selected) {
                    selected = it
                }
            }
        }
        compose.onNodeWithTag("routing-tab-global").assertIsSelected()
        compose.onNodeWithText("Feed").performClick()
        assertEquals("sub-a", selected)
        compose.onNodeWithTag("routing-tab-sub-a").assertIsSelected()
    }

    @Test
    fun settingsDialogAppliesRoutingToggleOnlyOnSave() {
        val changes = mutableListOf<Boolean>()
        var confirmed = 0
        compose.setContent {
            MaterialTheme {
                AddSubscriptionDialog(
                    onDismiss = {},
                    onConfirm = { _, _ -> confirmed++ },
                    initialUrl = sub.url,
                    editing = true,
                    routingAccepted = true,
                    routingError = "Роутинг подписки не принят: x",
                    onRoutingAcceptedChange = { changes.add(it) },
                )
            }
        }
        compose.onNodeWithText("Роутинг подписки не принят: x").assertExists()
        compose.onNodeWithText("Принимать роутинг").performClick()
        assertTrue(changes.isEmpty())
        compose.onNodeWithText("Сохранить").performClick()
        assertEquals(listOf(false), changes)
        assertEquals(1, confirmed)
    }

    @Test
    fun settingsDialogHidesToggleWithoutRouting() {
        compose.setContent {
            MaterialTheme { AddSubscriptionDialog(onDismiss = {}, onConfirm = { _, _ -> }, initialUrl = sub.url, editing = true) }
        }
        compose.onNodeWithText("Принимать роутинг").assertDoesNotExist()
    }

    @Test
    fun cardShowsOnlyRoutingError() {
        compose.setContent {
            MaterialTheme { SubscriptionCard(sub.copy(routingError = "Роутинг подписки не принят: x"), null, {}, {}, {}) }
        }
        compose.onNodeWithText("Роутинг подписки не принят: x").assertExists()
        compose.onNodeWithText("Принимать роутинг").assertDoesNotExist()
    }
}
