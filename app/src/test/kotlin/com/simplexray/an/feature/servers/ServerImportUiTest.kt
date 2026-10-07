package com.simplexray.an.feature.servers

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.simplexray.an.feature.servers.model.ServerCheckState
import com.simplexray.an.feature.servers.ui.ConfigListControls
import com.simplexray.an.feature.servers.ui.ImportServerDialog
import com.simplexray.an.feature.servers.ui.ServerGrouping
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h900dp", application = Application::class)
class ServerImportUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun editedNameIsSubmittedWithSelectedJsonEvenAfterAdvancedInputCollapses() {
        val json = """{"outbounds":[{"protocol":"freedom"}]}"""
        var imported: Pair<String, String?>? = null
        compose.setContent {
            MaterialTheme {
                ImportServerDialog(
                    json,
                    {},
                    { content, name ->
                        imported = content to name
                        false
                    },
                )
            }
        }

        compose.onNodeWithContentDescription("Название сервера").performTextInput("Office")
        compose.onNodeWithText("Расширенный ввод").performClick()
        compose.onNodeWithText("Сохранить").performClick()

        compose.runOnIdle { assertEquals(json to "Office", imported) }
    }

    @Test
    fun countryGroupingCanBeSelectedFromControls() {
        val grouping = mutableStateOf(ServerGrouping.SUBSCRIPTION)
        compose.setContent {
            MaterialTheme {
                ConfigListControls(
                    "",
                    {},
                    "name",
                    {},
                    grouping.value,
                    { grouping.value = it },
                    ServerCheckState(),
                    true,
                    {},
                )
            }
        }

        compose.onNodeWithText("По подпискам").performClick()
        compose.onNodeWithText("По странам").assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(ServerGrouping.COUNTRY, grouping.value) }
    }
}
