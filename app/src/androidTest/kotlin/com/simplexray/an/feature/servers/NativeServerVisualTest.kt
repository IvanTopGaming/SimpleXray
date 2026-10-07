package com.simplexray.an.feature.servers

import android.app.Application
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.simplexray.an.activity.MainActivity
import com.simplexray.an.prefs.Preferences
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NativeServerVisualTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val prefs = Preferences(app)
    private val fixture = File(app.filesDir, "visual-server.json")
    private var originalSelection: String? = null
    private var originalOrder = emptyList<String>()
    private lateinit var activity: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        originalSelection = prefs.selectedConfigPath
        originalOrder = prefs.configFilesOrder
        fixture.writeText("""{"outbounds":[{"tag":"proxy","protocol":"vless"}]}""")
        prefs.selectedConfigPath = fixture.absolutePath
        activity = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("Серверы").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1
        }
        compose.onNode(hasSetTextAction()).performTextInput("visual-server")
        compose.waitUntil(5000) {
            compose
                .onAllNodes(hasText("visual-server") and !hasSetTextAction())
                .fetchSemanticsNodes()
                .size == 1
        }
    }

    @After
    fun cleanup() {
        activity.close()
        fixture.delete()
        prefs.selectedConfigPath = originalSelection
        prefs.configFilesOrder = originalOrder
    }

    @Test
    fun editActionOccupiesSeparatedTrailingColumn() {
        val row =
            compose.onNodeWithTag("server-row-visual-server.json").fetchSemanticsNode().boundsInRoot
        val action =
            compose
                .onNodeWithContentDescription("Редактировать visual-server")
                .fetchSemanticsNode()
                .boundsInRoot
        val divider =
            compose
                .onNodeWithTag("server-action-divider-visual-server.json", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val density = app.resources.displayMetrics.density
        assertEquals(48f * density, action.width, density)
        assertEquals(row.right, action.right, density)
        assertEquals(action.left, divider.left, density)
        assertTrue(divider.height >= row.height - 2f * density)
        compose
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertCountEquals(0)
        compose.onNodeWithContentDescription("Удалить visual-server").assertDoesNotExist()
        compose.onNodeWithContentDescription("Редактировать visual-server").performClick()
        compose.onNodeWithContentDescription("Сохранить").assertIsDisplayed()
    }

    @Test
    fun manualServerCanBeDeletedThroughLongPress() {
        compose.onNode(hasText("visual-server") and !hasSetTextAction()).performTouchInput {
            longClick()
        }
        compose.onNodeWithText("Удалить сервер?").assertIsDisplayed()
        compose.onNodeWithText("Удалить", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { !fixture.exists() }
    }
}
