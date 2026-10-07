package com.simplexray.an.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.simplexray.an.activity.MainActivity
import org.junit.Rule
import org.junit.Test

class NativeNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun subscriptionsHaveTheirOwnTab() {
        compose.onNodeWithText("Подписки").performClick()
        compose.onNodeWithContentDescription("Добавить").assertIsDisplayed()
    }

    @Test
    fun emptyHomeOffersImportInsteadOfDisabledPower() {
        compose.onNodeWithText("Добро пожаловать\nв SimpleXray").assertIsDisplayed()
        compose.onNodeWithText("Вставить из буфера").assertIsDisplayed()
    }

    @Test
    fun diagnosticsAreAccessibleFromSettings() {
        compose.onNodeWithText("Настройки").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Журнал и отладка"))
        compose.onNodeWithText("Журнал и отладка").performClick()
        compose.onNodeWithText("Журнал и отладка").assertIsDisplayed()
    }

    @Test
    fun diagnosticsKeepsSearchAndAllowsClearingIt() {
        compose.onNodeWithText("Настройки").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Журнал и отладка"))
        compose.onNodeWithText("Журнал и отладка").performClick()
        compose.onNodeWithText("Поиск по журналу").performTextInput("needle")
        compose.onNodeWithContentDescription("Очистить поиск по журналу").performClick()
        compose.onNodeWithText("needle").assertDoesNotExist()
        compose.onNodeWithText("Скопировать журнал").assertIsDisplayed()
    }
}
