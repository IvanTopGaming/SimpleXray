package com.simplexray.an.feature.servers

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.simplexray.an.feature.servers.ui.ImportServerDialog
import com.simplexray.an.ui.theme.SimpleXrayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NativeImportVisualTest {
    @get:Rule val compose = createComposeRule()
    private val json = """{"outbounds":[{"protocol":"freedom"}]}"""

    @Test
    fun collapsingAdvancedInputPreservesJsonImport() {
        var imported: String? = null
        compose.setContent {
            SimpleXrayTheme(false) {
                ImportServerDialog(
                    json,
                    {},
                    { content, _ ->
                        imported = content
                        false
                    },
                )
            }
        }
        compose.onNodeWithText("Расширенный ввод").performClick()
        compose.onNode(hasText("Добавить") or hasText("Сохранить")).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(json, imported) }
    }

    @Test
    fun editingLinkReplacesPreviouslySelectedJsonImport() {
        var imported: String? = null
        val link = "vless://11111111-1111-1111-1111-111111111111@localhost:443?security=none"
        compose.setContent {
            SimpleXrayTheme(false) {
                ImportServerDialog(
                    json,
                    {},
                    { content, _ ->
                        imported = content
                        false
                    },
                )
            }
        }
        compose.onNodeWithContentDescription("Ссылка на сервер").performTextReplacement(link)
        compose.onNode(hasText("Добавить") or hasText("Сохранить")).performClick()
        compose.runOnIdle { assertEquals(link, imported) }
    }
}
