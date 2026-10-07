package com.simplexray.an.feature.servers

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.simplexray.an.activity.MainActivity
import com.simplexray.an.feature.subscriptions.model.Subscription
import com.simplexray.an.prefs.Preferences
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NativeEditorVisualTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val prefs = Preferences(app)
    private val manual = File(app.filesDir, "visual-editor-manual.json")
    private val owned = File(app.filesDir, "visual-editor-owned.json")
    private var originalSubscriptions = emptyList<Subscription>()
    private lateinit var activity: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        originalSubscriptions = prefs.subscriptions
        val config = """{"outbounds":[{"protocol":"freedom","tag":"proxy"}]}"""
        manual.writeText(config)
        owned.writeText(config)
        prefs.subscriptions =
            listOf(
                Subscription(
                    "visual-editor",
                    "Visual Provider",
                    "https://example.invalid/visual",
                    0,
                    listOf(owned.name),
                )
            )
        activity = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("Серверы").performClick()
    }

    @After
    fun cleanup() {
        activity.close()
        manual.delete()
        owned.delete()
        prefs.subscriptions = originalSubscriptions
    }

    @Test
    fun manualEditorIsAnInsetModalWithEqualFooterActions() {
        compose.onNodeWithContentDescription("Редактировать visual-editor-manual").performClick()
        val dialog = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        val name =
            compose
                .onNode(hasText("visual-editor-manual") and hasAnyAncestor(isDialog()))
                .fetchSemanticsNode()
                .boundsInRoot
        val cancel = compose.onNodeWithText("Отмена").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithText("Сохранить").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Editor form is inset within the modal",
            name.left > dialog.left && name.right < dialog.right,
        )
        assertTrue("Footer stays below the form", cancel.top > name.bottom)
        assertEquals("Editor footer actions have equal widths", cancel.width, save.width, 1f)
        compose.onNode(isDialog()).performTouchInput {
            click(androidx.compose.ui.geometry.Offset(1f, 1f))
        }
        compose.onNode(hasText("Серверы") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun subscriptionEditorModalOffersCloseWithoutSave() {
        compose.onNodeWithContentDescription("Просмотреть visual-editor-owned").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("Только просмотр · сервер из подписки").assertIsDisplayed()
        compose.onNodeWithText("Сохранить").assertDoesNotExist()
        compose.onNodeWithText("Закрыть").assertIsDisplayed().performClick()
        compose.onNode(hasText("Серверы") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun editorActionsRemainAboveSoftwareKeyboard() {
        manual.writeText(org.json.JSONObject(manual.readText()).toString(2))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val originalFlags = automation.serviceInfo.flags
        fun shell(command: String) =
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
                .bufferedReader()
                .use { it.readText().trim() }
        val originalKeyboard = shell("settings get secure show_ime_with_hard_keyboard")
        try {
            automation.serviceInfo =
                automation.serviceInfo.apply {
                    flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }
            shell("settings put secure show_ime_with_hard_keyboard 1")
            compose
                .onNodeWithContentDescription("Редактировать visual-editor-manual")
                .performClick()
            compose.onNodeWithContentDescription("Имя файла").performClick()
            compose.waitUntil(15000) {
                automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            }
            compose.waitForIdle()
            val keyboard =
                Rect().also { bounds ->
                    automation.windows
                        .first { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                        .getBoundsInScreen(bounds)
                }
            val window =
                Rect().also { bounds ->
                    automation.windows
                        .first {
                            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
                        }
                        .getBoundsInScreen(bounds)
                }
            val save =
                compose
                    .onNodeWithContentDescription("Сохранить")
                    .fetchSemanticsNode()
                    .boundsInWindow
            assertTrue(
                "Save action must not be covered by the keyboard",
                save.bottom + window.top <= keyboard.top,
            )
        } finally {
            shell("settings put secure show_ime_with_hard_keyboard $originalKeyboard")
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }
}
