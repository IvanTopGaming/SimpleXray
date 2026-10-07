package com.simplexray.an.feature.subscriptions

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.simplexray.an.activity.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NativeSubscriptionVisualTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        activity = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("Подписки").performClick()
        compose.onNodeWithContentDescription("Добавить").performClick()
        compose.onNodeWithText("Вручную").performClick()
    }

    @After
    fun cleanup() {
        activity.close()
    }

    @Test
    fun subscriptionLabelStaysAboveEditableField() {
        val label =
            compose
                .onNodeWithText("Ссылка подписки", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("The label must stay above the input", label.bottom < field.top)
    }

    @Test
    fun subscriptionActionsFillEqualHalvesOfDialogContent() {
        val cancel = compose.onNodeWithText("Отмена").fetchSemanticsNode().boundsInRoot
        val confirm = compose.onNodeWithText("Добавить").fetchSemanticsNode().boundsInRoot
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val dialog = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        assertEquals("Action widths must match", cancel.width, confirm.width, 1f)
        assertEquals("Cancel starts at the content edge", field.left, cancel.left, 1f)
        assertEquals(
            "Action row has equal outside margins",
            cancel.left - dialog.left,
            dialog.right - confirm.right,
            1f,
        )
        assertTrue("Each action fills half the available row", cancel.width > dialog.width * 0.35f)
    }
}
