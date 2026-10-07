package com.simplexray.an.ui.navigation

import android.app.Application
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.simplexray.an.activity.MainActivity
import com.simplexray.an.prefs.Preferences
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NativeDesignParityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val prefs = Preferences(app)
    private val fixture = File(app.filesDir, "parity-Amsterdam.json")
    private var originalSelection: String? = null
    private lateinit var activity: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        originalSelection = prefs.selectedConfigPath
        fixture.writeText("""{"outbounds":[{"tag":"proxy","protocol":"freedom"}]}""")
        prefs.selectedConfigPath = fixture.absolutePath
        activity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("session-totals").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After
    fun cleanup() {
        activity.close()
        fixture.delete()
        prefs.selectedConfigPath = originalSelection
    }

    @Test
    fun selectedServerPrecedesConnectionControl() {
        val server = compose.onNodeWithText("parity-Amsterdam").fetchSemanticsNode().boundsInRoot
        val power =
            compose.onNodeWithContentDescription("Подключить").fetchSemanticsNode().boundsInRoot
        assertTrue("Reference places server above power control", server.bottom < power.top)
    }

    @Test
    fun trafficTotalsRemainBelowSpeedsInSingleRow() {
        compose.onNodeWithTag("session-totals").assertIsDisplayed()
        val totals = compose.onNodeWithTag("session-totals").fetchSemanticsNode().boundsInRoot
        val speeds = compose.onNodeWithTag("live-speeds").fetchSemanticsNode().boundsInRoot
        assertTrue(totals.top > speeds.bottom)
        compose.onNodeWithText("Всего").assertDoesNotExist()
    }

    @Test
    fun sessionLabelsSitBelowTheirValues() {
        val totals = compose.onNodeWithTag("session-totals").fetchSemanticsNode().boundsInRoot
        val gap = 8f * app.resources.displayMetrics.density
        val cellWidth = (totals.width - 2 * gap) / 3
        listOf("↓ 0 МБ" to "Получено", "↑ 0 МБ" to "Отправлено", "00:00" to "Время")
            .forEachIndexed { index, (value, label) ->
                val valueBounds = compose.onNodeWithText(value).fetchSemanticsNode().boundsInRoot
                val labelBounds =
                    compose
                        .onNodeWithText(label)
                        .assertIsDisplayed()
                        .fetchSemanticsNode()
                        .boundsInRoot
                assertTrue("$label belongs below its value", labelBounds.top >= valueBounds.bottom)
                assertTrue(
                    "$label remains centered under its value",
                    kotlin.math.abs(labelBounds.center.x - valueBounds.center.x) < 2f,
                )
                val expectedCenter = totals.left + cellWidth / 2 + index * (cellWidth + gap)
                assertTrue(
                    "$label and its value are centered in an equal-width column",
                    kotlin.math.abs(valueBounds.center.x - expectedCenter) < 2f,
                )
            }
    }

    @Test
    fun importMenuAlignsWithTheRightEdgeOfAddOnBothTabs() {
        listOf("Серверы", "Подписки").forEach { tab ->
            compose.onNodeWithText(tab).performClick()
            val add = compose.onNodeWithText("＋ Добавить")
            val addNode = add.fetchSemanticsNode()
            val addPosition = addNode.positionOnScreen
            val addSize = addNode.size
            add.performClick()
            val menu = compose.onNodeWithTag("add-menu").fetchSemanticsNode()
            val menuPosition = menu.positionOnScreen
            assertTrue(
                "Menu ends at the Add button's right edge",
                kotlin.math.abs(menuPosition.x + menu.size.width - addPosition.x - addSize.width) <
                    2f,
            )
            assertTrue("Menu does not overflow the left edge", menuPosition.x >= 0f)
            assertTrue("Menu opens below Add", menuPosition.y >= addPosition.y + addSize.height)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("add-menu").assertDoesNotExist()
        }
    }

    @Test
    fun addActionHasVisibleLabelAndOpensImportMenu() {
        compose.onNodeWithText("Серверы").performClick()
        val add = compose.onNodeWithText("＋ Добавить").fetchSemanticsNode().boundsInRoot
        val search =
            compose.onNodeWithContentDescription("Поиск серверов").fetchSemanticsNode().boundsInRoot
        val density = app.resources.displayMetrics.density
        val gap = if (app.resources.configuration.screenWidthDp <= 370) 16f else 20f
        assertTrue(
            "Search retains the reference gap below the header",
            search.top - add.bottom >= (gap - 1f) * density,
        )
        compose.onNodeWithText("＋ Добавить").assertIsDisplayed().performClick()
        compose.onNodeWithText("Вручную").assertIsDisplayed()
        compose.onNodeWithText("Из буфера").assertIsDisplayed()
        compose.onNodeWithTag("manual-import-icon", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("clipboard-import-icon", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun settingsMatchReferenceHierarchyAndRemainNavigable() {
        compose.onNodeWithText("Настройки").performClick()
        compose
            .onNodeWithText("От повседневного подключения до параметров ядра.")
            .assertDoesNotExist()
        compose.onNodeWithText("Поиск: DNS, Mux, приложения…").assertDoesNotExist()
        compose.onNodeWithText("Подключение").performClick()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("DNS").assertIsDisplayed()
    }

    @Test
    fun subscriptionAndPingSettingsHaveDedicatedEntries() {
        compose.onNodeWithText("Настройки").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("HWID и обновление"))
        compose.onNodeWithText("HWID и обновление").assertIsDisplayed()
        compose.onNodeWithText("Автообновление подписок").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("TCP, HTTP GET и HTTP HEAD"))
        compose.onNodeWithText("Пинг").performClick()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Подключение").assertExists()
    }

    @Test
    fun connectivityCheckRemainsAvailableInConnectionSettings() {
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Подключение").performClick()
        compose.onNodeWithText("Проверить соединение").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun homeDescribesProxyOutboundRatherThanFirstDirectOutbound() {
        fixture.writeText(
            """{"outbounds":[{"tag":"direct","protocol":"freedom"},{"tag":"proxy","protocol":"vless"}]}"""
        )
        activity.recreate()
        compose.onNodeWithText("Ручной сервер · VLESS").assertIsDisplayed()
    }
}
