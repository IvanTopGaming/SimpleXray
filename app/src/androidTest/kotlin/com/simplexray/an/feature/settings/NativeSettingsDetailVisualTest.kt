package com.simplexray.an.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.simplexray.an.activity.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NativeSettingsDetailVisualTest {
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
    fun connectionHasNoMtuSetting() {
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Подключение").performClick()
        compose.onNodeWithText("Режим работы").assertIsDisplayed()
        compose.onNodeWithContentDescription("MTU").assertDoesNotExist()
    }
}
