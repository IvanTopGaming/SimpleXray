package com.simplexray.an.feature.routing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.state.RoutingEditor
import com.simplexray.an.feature.routing.ui.RoutingSettingsSection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeRoutingTest {
    @get:Rule val compose = createComposeRule()

    private fun show(): RoutingEditor {
        var stored: String? = null
        val editor = RoutingEditor({ stored }, { stored = it })
        compose.setContent {
            MaterialTheme {
                val scroll = rememberScrollState()
                Column(Modifier.height(600.dp).verticalScroll(scroll)) {
                    RoutingSettingsSection(editor)
                }
            }
        }
        return editor
    }

    @Test
    fun validEditsSaveAutomaticallyAndKeepOtherUnfinishedText() {
        val editor = show()
        compose
            .onNodeWithTag("routing-text-PROXY")
            .performScrollTo()
            .performTextReplacement("unfinished")
        compose.runOnIdle { assertTrue(editor.state.value.draft.rules.isEmpty()) }
        compose
            .onNodeWithTag("routing-text-DIRECT")
            .performScrollTo()
            .performTextReplacement("geoip:ru\ngeosite:google\nsuffix:ipwho.is")
        compose.runOnIdle {
            assertEquals(3, editor.state.value.draft.rules.size)
            assertTrue(editor.state.value.draft.rules.all { it.target == RouteTarget.DIRECT })
        }
        compose
            .onNodeWithTag("routing-text-PROXY")
            .performScrollTo()
            .assertTextContains("unfinished")
    }

    @Test
    fun invalidLineStaysEditableAndDoesNotReplaceSavedRules() {
        val editor = show()
        val field = compose.onNodeWithTag("routing-text-DIRECT")
        field.performScrollTo().performTextReplacement("suffix:example.com")
        compose.runOnIdle {
            assertEquals(listOf("example.com"), editor.state.value.draft.rules.single().values)
        }
        field.performTextReplacement("geoip:ru\nbroken")
        compose.onNode(hasText("Строка 2:", substring = true)).assertExists()
        field.assertTextContains("geoip:ru\nbroken")
        compose.runOnIdle {
            assertEquals(listOf("example.com"), editor.state.value.draft.rules.single().values)
        }
    }

    @Test
    fun arrowsKeepInvalidDraftAndSetBlockPriority() {
        val editor = show()
        compose
            .onNodeWithTag("routing-text-PROXY")
            .performScrollTo()
            .performTextReplacement("unfinished")
        compose.onNodeWithContentDescription("Поднять блок Прокси").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(RouteTarget.PROXY, RouteTarget.DIRECT, RouteTarget.BLOCK),
                editor.state.value.draft.blocks!!.map { it.target },
            )
        }
        compose
            .onNodeWithTag("routing-text-PROXY")
            .assertTextContains("unfinished")
            .performTextReplacement("suffix:pending.test")
        compose.runOnIdle {
            assertEquals(RouteTarget.PROXY, editor.state.value.draft.rules.first().target)
        }
    }

    @Test
    fun placeholdersAreNotRulesAndClearingABlockSavesItsEmptyState() {
        val editor = show()
        for (target in RouteTarget.entries) {
            val node =
                compose.onNodeWithTag("routing-text-$target").performScrollTo().fetchSemanticsNode()
            assertEquals("", node.config[SemanticsProperties.EditableText].text)
        }
        compose.runOnIdle { assertNull(editor.state.value.draft.blocks) }
        val field = compose.onNodeWithTag("routing-text-DIRECT").performScrollTo()
        field.performTextReplacement("suffix:example.com")
        compose.runOnIdle { assertEquals(1, editor.state.value.draft.rules.size) }
        field.performTextClearance()
        compose.runOnIdle {
            assertTrue(editor.state.value.draft.rules.isEmpty())
            assertEquals(
                "",
                editor.state.value.draft.blocks!!.single { it.target == RouteTarget.DIRECT }.text,
            )
        }
    }

    @Test
    fun fastInvalidEditIsNotReplacedByThePreviousAutosave() {
        val editor = show()
        val field = compose.onNodeWithTag("routing-text-DIRECT").performScrollTo()
        val setText =
            requireNotNull(field.fetchSemanticsNode().config[SemanticsActions.SetText].action)
        compose.runOnIdle {
            setText(AnnotatedString("suffix:x"))
            setText(AnnotatedString("suffix:x."))
        }
        field.assertTextContains("suffix:x.")
        compose.onNode(hasText("Строка 1:", substring = true)).assertExists()
        compose.runOnIdle {
            assertEquals(listOf("x"), editor.state.value.draft.rules.single().values)
        }
    }
}
