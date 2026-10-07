package com.simplexray.an.feature.routing

import android.app.Application
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
import com.simplexray.an.feature.routing.model.RoutingBlocks
import com.simplexray.an.feature.routing.model.RoutingServerRef
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.routing.server.RoutingServerOption
import com.simplexray.an.feature.routing.state.RoutingEditor
import com.simplexray.an.feature.routing.ui.RoutingSettingsSection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h900dp", application = Application::class)
class RoutingBlocksUiTest {
    @get:Rule val compose = createComposeRule()
    private var stored: String? = null

    private fun show(options: List<RoutingServerOption> = emptyList()): RoutingEditor {
        val editor = RoutingEditor({ stored }, { stored = it })
        compose.setContent {
            MaterialTheme {
                val scroll = rememberScrollState()
                Column(Modifier.height(600.dp).verticalScroll(scroll)) {
                    RoutingSettingsSection(editor, options)
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
    fun presetReloadReplacesMountedInvalidDraftAndItsError() {
        val editor = show()
        val field = compose.onNodeWithTag("routing-text-DIRECT").performScrollTo()
        field.performTextReplacement("unfinished")
        compose.onNode(hasText("Строка 1:", substring = true)).assertExists()
        val blocks =
            RoutingBlocks.fromRules(emptyList()).map {
                if (it.target == RouteTarget.DIRECT) it.copy(text = "suffix:imported.example")
                else it
            }
        compose.runOnIdle {
            stored =
                RoutingSettings(blocks = blocks, rules = blocks.flatMap(RoutingBlocks::parse))
                    .encode()
            editor.reload()
        }
        field.assertTextContains("suffix:imported.example")
        compose.onNode(hasText("Строка 1:", substring = true)).assertDoesNotExist()
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

    @Test
    fun pickerAddsBlockAndReplacingServerKeepsUnfinishedTextAndError() {
        val first = RoutingServerRef("first.json", "Первый")
        val second = RoutingServerRef("second.json", "Второй")
        val editor =
            show(
                listOf(
                    RoutingServerOption(first, "Подписка"),
                    RoutingServerOption(second, "Ручные серверы"),
                )
            )
        compose.onNodeWithTag("routing-add-server").performScrollTo().performClick()
        compose.onNodeWithTag("routing-server-search").performTextReplacement("Перв")
        compose.onNodeWithTag("routing-server-second.json").assertDoesNotExist()
        compose.onNodeWithTag("routing-server-first.json").performClick()
        var id = ""
        compose.runOnIdle {
            id = editor.state.value.draft.blocks!!.single { it.isServer }.id
            assertEquals(first, editor.state.value.draft.blocks!!.single { it.isServer }.server)
        }
        compose
            .onNodeWithTag("routing-text-$id")
            .performScrollTo()
            .performTextReplacement("unfinished")
        compose.onNodeWithTag("routing-choose-$id").performScrollTo().performClick()
        compose.onNodeWithTag("routing-server-second.json").performClick()
        compose.onNodeWithTag("routing-text-$id").performScrollTo().assertTextContains("unfinished")
        compose.onNode(hasText("Строка 1:", substring = true)).assertExists()
        compose.runOnIdle {
            assertEquals(second, editor.state.value.draft.blocks!!.single { it.id == id }.server)
            assertTrue(editor.state.value.draft.rules.isEmpty())
        }
    }

    @Test
    fun customBlockMovesBeyondThirdPositionAndConfirmsDeletingRules() {
        val first = RoutingServerRef("first.json", "Первый")
        val editor = show(listOf(RoutingServerOption(first, "Ручные серверы")))
        compose.runOnIdle { assertTrue(editor.addServerBlock(first)) }
        var id = ""
        compose.runOnIdle { id = editor.state.value.draft.blocks!!.single { it.isServer }.id }
        compose
            .onNodeWithTag("routing-text-$id")
            .performScrollTo()
            .performTextReplacement("suffix:special.example")
        repeat(2) {
            compose
                .onNodeWithContentDescription("Опустить блок Через сервер")
                .performScrollTo()
                .performClick()
        }
        compose.onNodeWithContentDescription("Опустить блок Через сервер").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(id, editor.state.value.draft.blocks!!.last().id) }
        compose.onNodeWithTag("routing-delete-$id").performScrollTo().performClick()
        compose.onNodeWithText("Удалить серверный блок?").assertExists()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertEquals(1, editor.state.value.draft.rules.size) }
        compose.onNodeWithTag("routing-delete-$id").performScrollTo().performClick()
        compose.onNodeWithText("Удалить", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertTrue(editor.state.value.draft.rules.isEmpty())
            assertEquals(3, RoutingSettings.decode(stored).blocks!!.size)
        }
    }

    @Test
    fun importedUnboundBlockExplainsHowToSelectServer() {
        val custom =
            com.simplexray.an.feature.routing.model.RoutingBlock(
                RouteTarget.PROXY,
                "suffix:example.com",
                "route_unbound",
            )
        val blocks = RoutingBlocks.fromRules(emptyList()) + custom
        stored =
            RoutingSettings(blocks = blocks, rules = blocks.flatMap(RoutingBlocks::parse)).encode()
        show()
        compose.onNodeWithTag("routing-choose-route_unbound").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Сервер не выбран").assertExists()
        compose.onNodeWithText("Выбери сервер для правил этого блока.").assertExists()
    }
}
