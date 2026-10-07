package com.simplexray.an.feature.subscriptions

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.feature.dashboard.ui.DashboardScreen
import com.simplexray.an.feature.subscriptions.model.Subscription
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.ui.theme.SimpleXrayTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeSubscriptionConnectionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun activeVpnCanDisconnectAfterBackgroundRemovesItsLastServer() {
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val prefs = Preferences(app)
        val originalSubs = prefs.subscriptions
        val originalSelection = prefs.selectedConfigPath
        val originalOrder = prefs.configFilesOrder
        val fixture = File(app.filesDir, "removed-subscription-fixture.json")
        val store = ViewModelStore()
        lateinit var model: MainViewModel
        var disconnects = 0
        var navigations = 0
        try {
            fixture.writeText("""{"outbounds":[{"protocol":"freedom","tag":"proxy"}]}""")
            prefs.selectedConfigPath = fixture.absolutePath
            prefs.subscriptions =
                listOf(
                    Subscription(
                        "removed",
                        "Removed",
                        "https://example.invalid",
                        0,
                        listOf(fixture.name),
                    )
                )
            compose.runOnUiThread {
                model = MainViewModel(app)
                store.put("main", model)
            }
            compose.waitUntil(10000) { model.selectedConfigFile.value == fixture }
            compose.setContent {
                SimpleXrayTheme(false) {
                    DashboardScreen(model, { disconnects++ }, { navigations++ }, {}, {})
                }
            }
            compose.runOnUiThread {
                model.setServiceEnabled(true)
                model.setControlMenuClickable(true)
            }
            compose.onNodeWithContentDescription("Отключить").assertIsDisplayed()
            fixture.delete()
            prefs.selectedConfigPath = null
            prefs.subscriptions = emptyList()
            compose.waitUntil(10000) {
                model.selectedConfigFile.value == null &&
                    model.configFiles.value.none { it == fixture }
            }
            compose.onNodeWithContentDescription("Отключить").assertIsDisplayed().performClick()
            assertEquals(1, disconnects)
            assertEquals(0, navigations)
        } finally {
            compose.runOnUiThread { store.clear() }
            fixture.delete()
            prefs.subscriptions = originalSubs
            prefs.selectedConfigPath = originalSelection
            prefs.configFilesOrder = originalOrder
        }
    }
}
