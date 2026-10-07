package com.simplexray.an.feature.servers

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.simplexray.an.activity.MainActivity
import com.simplexray.an.prefs.Preferences
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NativeServerChecksTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val prefs = Preferences(app)
    private val oldTarget = prefs.connectivityTestTarget
    private val oldTimeout = prefs.connectivityTestTimeout
    private val oldSelected = prefs.selectedConfigPath
    private val oldOrder = prefs.configFilesOrder
    private val good = File(app.filesDir, "availability-fixture-z-fast.json")
    private val bad = File(app.filesDir, "availability-fixture-a-blocked.json")
    private val outside = File(app.filesDir, "outside-availability-test.json")
    private var activity: ActivityScenario<MainActivity>? = null
    private var target: ProbeHttpFixture? = null

    @Before
    fun fixtures() {
        good.writeText("""{"outbounds":[{"tag":"proxy","protocol":"freedom"}]}""")
        bad.writeText("""{"outbounds":[{"tag":"proxy","protocol":"blackhole"}]}""")
        outside.writeText(good.readText())
        prefs.selectedConfigPath = good.absolutePath
        prefs.connectivityTestTimeout = 600
    }

    private fun launch(block: Boolean = false) {
        target = ProbeHttpFixture(block = block)
        prefs.connectivityTestTarget = target!!.url
        activity = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("Серверы").performClick()
        compose
            .onNodeWithContentDescription("Поиск серверов")
            .performTextInput("availability-fixture")
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("server-row-${good.name}").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After
    fun cleanup() {
        activity?.close()
        target?.close()
        listOf(good, bad, outside).forEach { it.delete() }
        prefs.connectivityTestTarget = oldTarget
        prefs.connectivityTestTimeout = oldTimeout
        prefs.selectedConfigPath = oldSelected
        prefs.configFilesOrder = oldOrder
    }

    @Test
    fun filteredBatchSortsRealResultsAndSharesSelectedLatencyWithHome() {
        launch()
        compose.onNodeWithText("Проверить доступность").assertIsEnabled().performClick()
        compose.waitUntil(10000) {
            compose
                .onAllNodesWithText("1 доступно · 1 не отвечает", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("latency-${good.name}").assertTextContains("мс", substring = true)
        compose
            .onNodeWithTag("latency-${bad.name}")
            .assertContentDescriptionContains("Не отвечает", substring = true)
        compose.onNodeWithContentDescription("Сортировка").performClick()
        compose.onNodeWithText("По задержке").assertIsEnabled().performClick()
        val goodBounds =
            compose.onNodeWithTag("server-row-${good.name}").fetchSemanticsNode().boundsInRoot
        val badBounds =
            compose.onNodeWithTag("server-row-${bad.name}").fetchSemanticsNode().boundsInRoot
        assertTrue(goodBounds.top < badBounds.top)
        assertEquals(good.absolutePath, prefs.selectedConfigPath)
        compose.onNodeWithText("Главная").performClick()
        compose.onNodeWithTag("latency-home").assertTextContains("мс", substring = true)
    }

    @Test
    fun cancellingBatchKeepsSelectionAndExposesCompletedProgress() {
        prefs.connectivityTestTimeout = 10000
        launch(block = true)
        compose.onNodeWithText("Проверить доступность").assertIsEnabled().performClick()
        compose.waitUntil(5000) {
            compose
                .onAllNodesWithText("Проверка ", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Отменить проверку").performClick()
        compose.waitUntil(5000) {
            compose
                .onAllNodesWithText("Проверка отменена", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Проверить доступность").assertIsEnabled()
        assertEquals(good.absolutePath, prefs.selectedConfigPath)
    }
}
