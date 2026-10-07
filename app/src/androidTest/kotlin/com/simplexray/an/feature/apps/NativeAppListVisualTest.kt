package com.simplexray.an.feature.apps

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.simplexray.an.feature.apps.model.AppRoutingMode
import com.simplexray.an.feature.apps.state.AppListViewModel
import com.simplexray.an.feature.apps.ui.AppListScreen
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.ui.theme.SimpleXrayTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NativeAppListVisualTest {
    @get:Rule val compose = createComposeRule()
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val prefs = Preferences(app)
    private val store = ViewModelStore()
    private lateinit var model: AppListViewModel
    private var originalApps: Set<String?>? = null
    private var originalBypass = false
    private var originalMode = AppRoutingMode.ALL

    @Before
    fun launch() {
        originalApps = prefs.apps
        originalBypass = prefs.bypassSelectedApps
        originalMode = prefs.appRoutingMode
        prefs.appRoutingMode = AppRoutingMode.EXCLUDE
        compose.runOnUiThread {
            model = AppListViewModel(app)
            store.put("apps", model)
        }
        compose.setContent { SimpleXrayTheme(false) { AppListScreen(model, onBackClick = {}) } }
        compose.waitUntil(45000) { !model.isLoading }
    }

    @After
    fun cleanup() {
        compose.runOnUiThread { store.clear() }
        prefs.apps = originalApps
        prefs.appRoutingMode = originalMode
        prefs.bypassSelectedApps = originalBypass
    }

    @Test
    fun appSettingsUseAlignedGroupsAndPersistentSearch() {
        val header = compose.onNodeWithTag("app-page-header").fetchSemanticsNode().boundsInRoot
        val group = compose.onNodeWithTag("app-mode-group").fetchSemanticsNode().boundsInRoot
        val search =
            compose
                .onNodeWithContentDescription("Найти приложение")
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(header.left, group.left, 0.5f)
        assertEquals(group.left, search.left, 0.5f)
        compose.onNodeWithText("VPN ДЛЯ ПРИЛОЖЕНИЙ").assertIsDisplayed()
    }

    @Test
    fun installedApplicationTogglePersistsItsActualPackage() {
        val pkg = model.filteredList.first()
        compose.onNodeWithTag("app-list").performScrollToNode(hasTestTag("app-${pkg.packageName}"))
        compose.onNodeWithTag("app-${pkg.packageName}").performClick()
        compose.waitUntil(5000) { (prefs.apps?.contains(pkg.packageName) == true) != pkg.selected }
        compose.onNodeWithTag("app-${pkg.packageName}").assertIsToggleable()
        if (pkg.selected) compose.onNodeWithTag("app-${pkg.packageName}").assertIsOff()
        else compose.onNodeWithTag("app-${pkg.packageName}").assertIsOn()
    }

    @Test
    fun selectedApplicationStateIncludesPackagesHiddenBySearch() {
        val pkg = model.filteredList.first()
        compose.runOnUiThread {
            model.onPackageSelected(pkg, true)
            model.onSearchQueryChange("no-such-installed-app-parity")
            org.junit.Assert.assertTrue(model.hasSelectedApplications)
        }
        compose.onNodeWithText("Все приложения используют VPN.").assertDoesNotExist()
    }
}
