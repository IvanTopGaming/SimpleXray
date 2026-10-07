package com.simplexray.an.feature.servers.manual

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import com.simplexray.an.feature.servers.manual.schema.ManualSchemaRegistry
import com.simplexray.an.feature.servers.ui.manual.ManualServerDialog
import com.simplexray.an.ui.theme.SimpleXrayTheme
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
@OptIn(ExperimentalTestApi::class)
class ManualServerDialogTest {
    @get:Rule
    val compose =
        createAndroidComposeRule<ComponentActivity>(
            effectContext =
                StandardTestDispatcher() +
                    object : MotionDurationScale {
                        override val scaleFactor = 0f
                    }
        )

    @Test
    fun protocolSwitchShowsOwnFieldsAndRestoresDraft() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SimpleXrayTheme(dark = false) { ManualServerDialog({}, { _, _ -> true }) }
        }
        ManualSchemaRegistry.fromAssets(compose.activity)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("manual.name").performTextReplacement("My profile")
        compose.onNodeWithTag("manual.protocol").performSemanticsAction(SemanticsActions.OnClick) {
            it()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("manual.protocol.option.wireguard").performSemanticsAction(
            SemanticsActions.OnClick
        ) {
            it()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("manual.settings.wireguard.secretKey.input").assertExists()
        compose.onNodeWithTag("manual.settings.vless.id.input").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("manual.name").assertTextEquals("My profile")
        compose.onNodeWithTag("manual.settings.wireguard.secretKey.input").assertExists()
    }

    @Test
    fun invalidRequiredInputDoesNotReachImport() {
        var imports = 0
        compose.setContent {
            SimpleXrayTheme(dark = false) {
                ManualServerDialog(
                    {},
                    { _, _ ->
                        imports++
                        true
                    },
                )
            }
        }
        ManualSchemaRegistry.fromAssets(compose.activity)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(3000) {
            compose
                .onAllNodesWithText("Адрес сервера: заполни поле")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertEquals(0, imports)
        compose.onNodeWithText("Сохранить").assertIsEnabled()
    }

    @Test
    fun selectingCurrentProtocolKeepsEnteredCredentials() {
        compose.setContent {
            SimpleXrayTheme(dark = false) { ManualServerDialog({}, { _, _ -> true }) }
        }
        compose.mainClock.advanceTimeByFrame()
        val id = "b831381d-6324-4d53-ad4f-8cda48b30811"
        compose.onNodeWithTag("manual.settings.vless.id.input").performSemanticsAction(
            SemanticsActions.SetText
        ) {
            it(AnnotatedString(id))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("manual.protocol").performSemanticsAction(SemanticsActions.OnClick) {
            it()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("manual.protocol.option.vless").performSemanticsAction(
            SemanticsActions.OnClick
        ) {
            it()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("manual.settings.vless.id.input").assert(hasText(id))
    }

    @Test
    fun validFormSavesWithoutLaunchingAnInstalledCore() {
        var saved: String? = null
        var dismissed = false
        compose.setContent {
            SimpleXrayTheme(dark = false) {
                ManualServerDialog(
                    { dismissed = true },
                    { content, _ ->
                        saved = content
                        true
                    },
                )
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Дополнительные параметры").assertDoesNotExist()
        compose.onNodeWithText("Свернуть").assertDoesNotExist()
        compose.onNodeWithText("Развернуть").assertDoesNotExist()
        compose.onNodeWithTag("manual.stream.sockopt.mark.input").assertExists()
        compose.onNodeWithTag("manual.settings.vless.address.input").performSemanticsAction(
            SemanticsActions.SetText
        ) {
            it(AnnotatedString("127.0.0.1"))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("manual.settings.vless.id.input").performSemanticsAction(
            SemanticsActions.SetText
        ) {
            it(AnnotatedString("manual-fixture"))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(3000) { dismissed }
        org.junit.Assert.assertTrue(saved.orEmpty().contains("manual-fixture"))
        val stream =
            com.google.gson.JsonParser.parseString(saved)
                .asJsonObject
                .getAsJsonArray("outbounds")[0]
                .asJsonObject
                .getAsJsonObject("streamSettings")
        org.junit.Assert.assertFalse(stream.has("sockopt"))
        org.junit.Assert.assertFalse(stream.has("rawSettings"))
        org.junit.Assert.assertFalse(stream.has("finalmask"))
    }
}
