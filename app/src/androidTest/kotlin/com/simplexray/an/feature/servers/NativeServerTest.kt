package com.simplexray.an.feature.servers

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.simplexray.an.activity.MainActivity
import com.simplexray.an.feature.subscriptions.model.Subscription
import com.simplexray.an.prefs.Preferences
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NativeServerTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val prefs = Preferences(app)
    private lateinit var activity: ActivityScenario<MainActivity>
    private val fixtures = listOf("test-Amsterdam.json", "test-Tokyo.json")
    private val config = """{"outbounds":[{"protocol":"freedom","tag":"proxy"}]}"""
    private var initialFiles = emptySet<String>()
    private var originalSubscriptions = emptyList<Subscription>()
    private var originalSelection: String? = null
    private var originalOrder = emptyList<String>()

    @Before
    fun setUp() {
        initialFiles = app.filesDir.listFiles().orEmpty().map { it.name }.toSet()
        originalSubscriptions = prefs.subscriptions
        originalSelection = prefs.selectedConfigPath
        originalOrder = prefs.configFilesOrder
        fixtures.forEach { File(app.filesDir, it).writeText(config) }
        prefs.subscriptions =
            listOf(
                Subscription(
                    "test-sub",
                    "Test Provider",
                    "https://example.invalid/sub",
                    0,
                    listOf(fixtures[0]),
                )
            )
        prefs.selectedConfigPath = null
        activity = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        activity.close()
        fixtures.forEach { File(app.filesDir, it).delete() }
        app.filesDir
            .listFiles()
            .orEmpty()
            .filter { it.extension == "json" && it.name !in initialFiles }
            .forEach { it.delete() }
        prefs.subscriptions = originalSubscriptions
        prefs.selectedConfigPath = originalSelection
        prefs.configFilesOrder = originalOrder
    }

    @Test
    fun searchCanBeClearedWithoutLosingServers() {
        compose.onNodeWithText("Серверы").performClick()
        compose.onNodeWithContentDescription("Поиск серверов").performTextInput("Tokyo")
        compose.onNodeWithText("test-Amsterdam").assertDoesNotExist()
        compose.onNodeWithContentDescription("Очистить поиск").performClick()
        compose.onNodeWithText("test-Amsterdam").assertIsDisplayed()
        compose.onNodeWithText("test-Tokyo").assertIsDisplayed()
    }

    @Test
    fun subscriptionCardOpensOnlyItsServers() {
        compose.onNodeWithText("Подписки").performClick()
        compose.onNodeWithText("Test Provider").performClick()
        compose.onNodeWithText("test-Amsterdam").assertIsDisplayed()
        compose.onNodeWithText("test-Tokyo").assertDoesNotExist()
        compose.onNodeWithContentDescription("Сбросить фильтр подписки").performClick()
        compose.onNodeWithText("test-Tokyo").assertIsDisplayed()
    }

    @Test
    fun subscriptionServerIsReadOnly() {
        compose.onNodeWithText("Серверы").performClick()
        compose.onNodeWithContentDescription("Просмотреть test-Amsterdam").performClick()
        compose.onNodeWithText("Только просмотр · сервер из подписки").assertIsDisplayed()
        compose.onNodeWithContentDescription("Сохранить").assertDoesNotExist()
    }

    @Test
    fun manualAddStartsWithConnectionLink() {
        compose.onNodeWithText("Серверы").performClick()
        compose.onNodeWithContentDescription("Добавить").performClick()
        compose.onNodeWithText("Вручную").performClick()
        compose.onNodeWithContentDescription("Ссылка на сервер").assertIsDisplayed()
        compose.onNodeWithText("Расширенный ввод").assertIsDisplayed()
    }

    @Test
    fun subscriptionUrlCanBeEditedWithoutRenamingServers() {
        compose.onNodeWithText("Подписки").performClick()
        compose.onNodeWithContentDescription("Изменить ссылку подписки").performClick()
        compose
            .onNodeWithContentDescription("Ссылка подписки")
            .performTextReplacement("https://example.invalid/updated")
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(5000) {
            prefs.subscriptions.firstOrNull()?.url == "https://example.invalid/updated"
        }
        org.junit.Assert.assertTrue(File(app.filesDir, fixtures[0]).exists())
    }

    @Test
    fun invalidServerImportStaysInDialog() {
        compose.onNodeWithText("Серверы").performClick()
        compose.onNodeWithContentDescription("Добавить").performClick()
        compose.onNodeWithText("Вручную").performClick()
        compose.onNodeWithContentDescription("Ссылка на сервер").performTextInput("not-a-server")
        compose.onNodeWithText("Сохранить").performClick()
        compose
            .onNodeWithText("Не удалось импортировать. Проверь ссылку или JSON.")
            .assertIsDisplayed()
    }

    @Test
    fun headingAndSearchShareLeftEdge() {
        compose.onNodeWithText("Серверы").performClick()
        val heading =
            compose
                .onNode(hasText("Серверы") and !hasClickAction())
                .fetchSemanticsNode()
                .boundsInRoot
                .left
        val search = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.left
        org.junit.Assert.assertEquals(search, heading, 0.5f)
    }

    @Test
    fun subscriptionNavigationResetsOldSearch() {
        compose.onNodeWithText("Серверы").performClick()
        compose.onNodeWithContentDescription("Поиск серверов").performTextInput("Tokyo")
        compose.onNodeWithText("Подписки").performClick()
        compose.onNodeWithText("Test Provider").performClick()
        compose.onNodeWithText("test-Amsterdam").assertIsDisplayed()
    }
}
