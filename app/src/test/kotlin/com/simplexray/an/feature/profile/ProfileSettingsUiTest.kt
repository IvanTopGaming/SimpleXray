package com.simplexray.an.feature.profile

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.google.gson.JsonParser
import com.simplexray.an.feature.profile.ui.ProfileSettingsSection
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.ui.theme.SimpleXrayTheme
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
@OptIn(ExperimentalTestApi::class)
class ProfileSettingsUiTest {
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
    fun explicitSaveReportsErrorsAndCopyUsesSavedFullProfileInsteadOfDraft() {
        val prefs = Preferences(compose.activity)
        prefs.profileOverridesJson = """{"log":{"loglevel":"warning"}}"""
        val server =
            File(compose.activity.filesDir, "profile-ui.json").apply {
                writeText(
                    """{"outbounds":[{"protocol":"socks","settings":{"servers":[{"address":"edge.example","port":1080}]}}]}"""
                )
            }
        prefs.selectedConfigPath = server.absolutePath
        compose.setContent {
            SimpleXrayTheme(dark = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ProfileSettingsSection(prefs, server)
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("profile-preview").fetchSemanticsNodes().isNotEmpty()
        }
        val saved = prefs.profileOverridesJson
        compose
            .onNodeWithTag("profile-overrides")
            .performScrollTo()
            .performTextReplacement("broken")
        assertEquals(saved, prefs.profileOverridesJson)
        compose.onNodeWithText("Сохранить переопределения").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("profile-error").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(saved, prefs.profileOverridesJson)
        compose
            .onNodeWithTag("profile-overrides")
            .performScrollTo()
            .performTextReplacement("""{"log":{"loglevel":"error"}}""")
        compose.onNodeWithText("Сохранить переопределения").performScrollTo().performClick()
        compose.waitUntil(5_000) { prefs.profileOverridesJson != saved }
        compose
            .onNodeWithTag("profile-overrides")
            .performScrollTo()
            .performTextReplacement("""{"log":{"loglevel":"debug"}}""")
        compose.onNodeWithText("Копировать JSON профиля").performScrollTo().performClick()
        val clipboard =
            compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.waitUntil(5_000) { clipboard.primaryClip != null }
        val copied =
            JsonParser.parseString(clipboard.primaryClip!!.getItemAt(0).text.toString())
                .asJsonObject
        assertEquals("error", copied.getAsJsonObject("log")["loglevel"].asString)
        assertTrue(copied.has("inbounds"))
        assertTrue(copied.has("outbounds"))
        assertTrue(copied.has("dns"))
        assertTrue(copied.has("routing"))
    }
}
