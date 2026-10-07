package com.simplexray.an.feature.apps

import android.Manifest
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.simplexray.an.feature.apps.model.AppRoutingMode
import com.simplexray.an.feature.apps.state.AppListViewModel
import com.simplexray.an.feature.apps.ui.AppListScreen
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.ui.theme.SimpleXrayTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppRoutingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app
        get() = RuntimeEnvironment.getApplication()

    private val prefs
        get() = Preferences(app)

    private lateinit var model: AppListViewModel

    @Before
    fun installApplications() {
        listOf("com.example.selected", "com.example.other").forEach { name ->
            shadowOf(app.packageManager)
                .installPackage(
                    PackageInfo().apply {
                        packageName = name
                        applicationInfo =
                            ApplicationInfo().apply {
                                packageName = name
                                nonLocalizedLabel = name
                            }
                        requestedPermissions = arrayOf(Manifest.permission.INTERNET)
                    }
                )
        }
    }

    private fun launch(onBack: () -> Unit = {}) {
        compose.setContent {
            model = remember { AppListViewModel(app) }
            SimpleXrayTheme(false) { AppListScreen(model, onBackClick = onBack) }
        }
        compose.waitUntil(10000) { !model.isLoading }
    }

    @Test
    fun allDisablesRetainedSelectionAndIncludeRestoresIt() {
        prefs.apps = setOf("com.example.selected")
        prefs.appRoutingMode = AppRoutingMode.EXCLUDE
        launch()
        compose.onNodeWithText("Кроме выбранных").performClick()
        compose.onNodeWithText("Все приложения").performClick()
        compose.onNodeWithTag("app-com.example.selected").assertIsOn().assertIsNotEnabled()
        assertEquals(setOf("com.example.selected"), prefs.apps)
        compose.onNodeWithText("Все приложения").performClick()
        compose.onNodeWithText("Только выбранные").performClick()
        compose.onNodeWithTag("app-com.example.selected").assertIsOn().assertIsEnabled()
    }

    @Test
    fun emptyIncludeShowsErrorAndBlocksBackButton() {
        prefs.apps = emptySet()
        prefs.appRoutingMode = AppRoutingMode.EXCLUDE
        var left = false
        launch { left = true }
        compose.onNodeWithText("Кроме выбранных").performClick()
        compose.onNodeWithText("Только выбранные").performClick()
        compose.onNodeWithText("Выберите хотя бы одно приложение для VPN.").assertExists()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.runOnIdle { assertFalse(left) }
    }
}
