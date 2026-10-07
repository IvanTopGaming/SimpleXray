package com.simplexray.an.feature.settings

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.ui.navigation.AppNavHost
import com.simplexray.an.ui.theme.SimpleXrayTheme
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test

class NativeRestoreTest {
    @get:Rule val compose = createComposeRule()
    private val stores = mutableListOf<ViewModelStore>()

    private fun newModel(app: Application): MainViewModel =
        MainViewModel(app).also { model ->
            stores += ViewModelStore().apply { put("main", model) }
        }

    @After
    fun clearModels() {
        stores.forEach { it.clear() }
    }

    @Test
    fun appListRouteSurvivesFreshMainViewModel() {
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val model = remember { newModel(app) }
            SimpleXrayTheme(false) { AppNavHost(model) }
        }
        compose.onNodeWithText("Настройки").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Приложения"))
        compose.onNodeWithText("Приложения").performClick()
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Назад").assertIsDisplayed()
    }

    @Test
    fun editorRouteCarriesFileAcrossFreshMainViewModel() {
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val file = File(app.filesDir, "test-restore.json")
        file.writeText("""{"log":{},"outbounds":[{"protocol":"freedom"}]}""")
        try {
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                val model = remember { newModel(app) }
                SimpleXrayTheme(false) { AppNavHost(model) }
            }
            compose.onNodeWithText("Серверы").performClick()
            compose.onNodeWithContentDescription("Редактировать test-restore").performClick()
            compose.waitForIdle()
            restoration.emulateSavedInstanceStateRestore()
            compose
                .onNode(hasText("test-restore") and hasAnyAncestor(isDialog()))
                .assertIsDisplayed()
        } finally {
            file.delete()
        }
    }
}
