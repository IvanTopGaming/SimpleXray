package com.simplexray.an.feature.logs

import android.content.Intent
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.simplexray.an.activity.MainActivity
import com.simplexray.an.service.TProxyService
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NativeLogVisualTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        activity = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun cleanup() {
        activity.close()
    }

    @Test
    fun exportActionMatchesAddButtonSurfaceAndHeight() {
        val density =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .resources
                .displayMetrics
                .density
        compose.onNodeWithText("Серверы").performClick()
        val add = compose.onNodeWithText("＋ Добавить").assertIsDisplayed()
        val addBounds = add.fetchSemanticsNode().boundsInRoot
        val addPixels = add.captureToImage().toPixelMap()
        val addColor = addPixels[addPixels.width / 2, (4f * density).toInt()]

        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Журнал и отладка").performScrollTo().performClick()
        val export = compose.onNodeWithText("Отправить").assertIsDisplayed().assertHasClickAction()
        val exportPixels = export.captureToImage().toPixelMap()
        assertEquals(addBounds.height, export.fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(addColor, exportPixels[exportPixels.width / 2, (4f * density).toInt()])
    }

    @Test
    fun logPanelPrecedesFullWidthCopyAndFilteringUsesLiveEntries() {
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Журнал и отладка").performScrollTo().performClick()
        val panel =
            compose
                .onNodeWithTag("diagnostic-log")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val copy =
            compose
                .onNodeWithText("Скопировать журнал")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue("Copy belongs below the bordered log panel", copy.top >= panel.bottom)
        assertTrue("Copy uses the full panel width", abs(copy.width - panel.width) < 2f)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.sendBroadcast(
            Intent(TProxyService.ACTION_LOG_UPDATE)
                .setPackage(context.packageName)
                .putStringArrayListExtra(
                    TProxyService.EXTRA_LOG_DATA,
                    arrayListOf("parity-log-needle", "parity-log-other"),
                )
        )
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("parity-log-needle").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("parity-log-other").assertIsDisplayed()
        compose
            .onNodeWithContentDescription("Поиск по журналу")
            .performTextInput("parity-log-needle")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("parity-log-other").fetchSemanticsNodes().isEmpty()
        }
        compose.onNode(hasText("parity-log-needle") and !hasSetTextAction()).assertIsDisplayed()
    }
}
