package com.simplexray.an.feature.subscriptions

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.simplexray.an.feature.subscriptions.data.SubscriptionRefreshScheduler
import com.simplexray.an.feature.subscriptions.model.Subscription
import com.simplexray.an.feature.subscriptions.model.SubscriptionUsage
import com.simplexray.an.feature.subscriptions.state.SubscriptionSyncState
import com.simplexray.an.feature.subscriptions.ui.SubscriptionCard
import com.simplexray.an.feature.subscriptions.ui.SubscriptionSettingsSection
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.ui.theme.SimpleXrayTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeSubscriptionUsageTest {
    @get:Rule val compose = createComposeRule()
    private val sub = Subscription("usage", "Provider", "https://example.invalid", 0, emptyList())

    @Test
    fun providerUsageAppearsInCardWithAccessibleProgress() {
        compose.setContent {
            SimpleXrayTheme(false) {
                SubscriptionCard(
                    sub.copy(usage = SubscriptionUsage(100, 300, 1000, 0)),
                    null,
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithText("400 Б / 1000 Б").assertIsDisplayed()
        compose.onNodeWithText("Осталось 600 Б").assertIsDisplayed()
        compose.onNodeWithText("Без срока действия").assertIsDisplayed()
        compose
            .onNodeWithContentDescription("Использование трафика")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(.4f, 0f..1f),
                )
            )
    }

    @Test
    fun expiredAndExhaustedSubscriptionIsNotPresentedAsHealthy() {
        compose.setContent {
            SimpleXrayTheme(false) {
                SubscriptionCard(
                    sub.copy(usage = SubscriptionUsage(100, 1000, 1000, 1)),
                    null,
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithText("Лимит трафика исчерпан").assertIsDisplayed()
        compose.onNodeWithText("Срок действия истёк").assertIsDisplayed()
    }

    @Test
    fun missingHeaderDoesNotInventUnlimitedTraffic() {
        compose.setContent { SimpleXrayTheme(false) { SubscriptionCard(sub, null, {}, {}, {}) } }
        compose.onNodeWithText("Нет данных о трафике").assertIsDisplayed()
        compose.onNodeWithText("Срок действия неизвестен").assertIsDisplayed()
        compose.onNodeWithText("Безлимитный трафик").assertDoesNotExist()
    }

    @Test
    fun backgroundSuccessReplacesAnOldForegroundError() {
        val subscription = mutableStateOf(sub.copy(lastError = "Provider unavailable"))
        compose.setContent {
            SimpleXrayTheme(false) {
                SubscriptionCard(
                    subscription.value,
                    SubscriptionSyncState(error = "Provider unavailable"),
                    {},
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithText("Provider unavailable").assertIsDisplayed()
        compose.runOnIdle {
            subscription.value =
                sub.copy(lastUpdated = System.currentTimeMillis(), lastError = null)
        }
        compose.onNodeWithText("Provider unavailable").assertDoesNotExist()
    }

    @Test
    fun subscriptionActionsDoNotWrapIndividualWords() {
        var refreshed = false
        var edited = false
        var deleted = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 1.3f)
            ) {
                SimpleXrayTheme(false) {
                    Box(Modifier.width(312.dp)) {
                        SubscriptionCard(
                            sub,
                            null,
                            { refreshed = true },
                            { edited = true },
                            {},
                            { deleted = true },
                        )
                    }
                }
            }
        }
        listOf("Обновить", "Настроить", "Удалить").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(
                SemanticsActions.GetTextLayoutResult
            ) {
                it(layouts)
            }
            assertEquals("Action layout must adapt to large fonts", 1, layouts.single().lineCount)
            val layout = layouts.single()
            assertTrue(
                "$label must fit its visible bounds",
                layout.getLineLeft(0) >= 0 && layout.getLineRight(0) <= layout.size.width,
            )
            assertFalse("$label must not be truncated", layout.isLineEllipsized(0))
            compose.onNodeWithText(label).performClick()
        }
        assertTrue(refreshed && edited && deleted)
    }

    @Test
    fun subscriptionAutoUpdateSwitchPersistsAndCanBeTurnedOff() {
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val prefs = Preferences(app)
        val old = prefs.autoUpdateSubscriptions
        try {
            prefs.autoUpdateSubscriptions = true
            compose.setContent { SimpleXrayTheme(false) { SubscriptionSettingsSection(prefs) } }
            compose.onNodeWithText("Автообновление подписок").performClick()
            compose.waitUntil(5000) { !prefs.autoUpdateSubscriptions }
            compose.onNodeWithText("Автообновление подписок").assertIsOff()
            assertFalse(Preferences(app).autoUpdateSubscriptions)
        } finally {
            prefs.autoUpdateSubscriptions = old
            SubscriptionRefreshScheduler.reconcile(app)
        }
    }
}
