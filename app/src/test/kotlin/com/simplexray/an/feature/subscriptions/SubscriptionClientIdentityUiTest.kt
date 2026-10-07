package com.simplexray.an.feature.subscriptions

import android.app.Application
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.simplexray.an.BuildConfig
import com.simplexray.an.feature.subscriptions.data.SubscriptionClientIdentity
import com.simplexray.an.feature.subscriptions.data.SubscriptionIdentityField
import com.simplexray.an.feature.subscriptions.ui.SubscriptionSettingsSection
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.ui.theme.SimpleXrayTheme
import java.util.UUID
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
class SubscriptionClientIdentityUiTest {
    @get:Rule
    val compose =
        createAndroidComposeRule<ComponentActivity>(
            effectContext =
                StandardTestDispatcher() +
                    object : MotionDurationScale {
                        override val scaleFactor = 0f
                    }
        )

    private fun launchSettings(): Preferences {
        val prefs = Preferences(compose.activity)
        compose.setContent {
            SimpleXrayTheme(dark = false) { SubscriptionSettingsSection(prefs) }
        }
        compose.onNodeWithText("HWID, устройство и User-Agent").performClick()
        compose.mainClock.advanceTimeByFrame()
        return prefs
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performClick()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun edit(label: String) {
        compose.onNodeWithContentDescription(label).performSemanticsAction(
            SemanticsActions.OnClick
        ) {
            it()
        }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun fieldsSaveIndividuallyAndRemainAvailableAfterReopening() {
        val prefs = launchSettings()
        listOf(
                "HWID" to "custom-device-123",
                "Модель устройства" to "Pixel 9",
                "Операционная система" to "Android",
                "Версия ОС" to "15",
                "User-Agent" to "Example/2.0",
            )
            .forEach { (label, value) ->
                edit(label)
                compose.onNode(hasSetTextAction()).performTextReplacement(value)
                click("Сохранить")
            }
        click("Закрыть")
        click("HWID, устройство и User-Agent")
        val stored =
            SubscriptionClientIdentity.parse(
                Preferences(compose.activity).subscriptionClientIdentityJson
            )
        assertEquals("custom-device-123", stored[SubscriptionIdentityField.HWID])
        assertEquals("Pixel 9", stored[SubscriptionIdentityField.MODEL])
        assertEquals("Android", stored[SubscriptionIdentityField.OS])
        assertEquals("15", stored[SubscriptionIdentityField.OS_VERSION])
        assertEquals("Example/2.0", stored[SubscriptionIdentityField.USER_AGENT])
        edit("Модель устройства")
        compose.onNode(hasSetTextAction()).assertTextEquals("Pixel 9")
        compose.onNode(hasSetTextAction()).performTextClearance()
        click("Сохранить")
        assertEquals(
            "",
            SubscriptionClientIdentity.parse(prefs.subscriptionClientIdentityJson)[
                    SubscriptionIdentityField.MODEL],
        )
    }

    @Test
    fun generatedHwidRequiresSaveAndInvalidInputCannotReplaceTheSavedValue() {
        val prefs = launchSettings()
        val original = prefs.subscriptionInstallationId
        edit("HWID")
        click("Сгенерировать")
        click("Отмена")
        assertEquals(
            "",
            SubscriptionClientIdentity.parse(prefs.subscriptionClientIdentityJson)[
                    SubscriptionIdentityField.HWID],
        )
        edit("HWID")
        click("Сгенерировать")
        click("Сохранить")
        val generated =
            SubscriptionClientIdentity.parse(prefs.subscriptionClientIdentityJson)[
                    SubscriptionIdentityField.HWID]
        assertEquals(4, UUID.fromString(generated).version())
        assertNotEquals(original, generated)
        edit("HWID")
        compose.onNode(hasSetTextAction()).assertTextEquals(generated)
        compose.onNode(hasSetTextAction()).performTextReplacement("invalid!")
        click("Сохранить")
        compose.onNodeWithText("HWID: от 10 до 64", substring = true).assertIsDisplayed()
        assertEquals(
            generated,
            SubscriptionClientIdentity.parse(prefs.subscriptionClientIdentityJson)[
                    SubscriptionIdentityField.HWID],
        )
        assertEquals(original, prefs.subscriptionInstallationId)
    }

    @Test
    fun corruptProfileCanBeResetWithoutChangingInstallationHwid() {
        val prefs = Preferences(compose.activity)
        val original = prefs.subscriptionInstallationId
        prefs.subscriptionClientIdentityJson = "broken"
        launchSettings()
        compose.onNodeWithText("Данные повреждены.", substring = true).assertIsDisplayed()
        click("Сбросить данные клиента")
        edit("Модель устройства")
        compose.onNode(hasSetTextAction()).performTextReplacement("Chosen model")
        click("Сохранить")
        assertEquals(
            "Chosen model",
            SubscriptionClientIdentity.parse(prefs.subscriptionClientIdentityJson)[
                    SubscriptionIdentityField.MODEL],
        )
        assertEquals(original, prefs.subscriptionInstallationId)
    }

    @Test
    fun defaultsFillEveryEditorAndClearingAnOverrideRestoresTheDeviceValue() {
        val prefs = launchSettings()
        val original = prefs.subscriptionInstallationId
        listOf(
                "HWID" to original,
                "Модель устройства" to Build.MODEL,
                "Операционная система" to "Android",
                "Версия ОС" to Build.VERSION.RELEASE,
                "User-Agent" to "SimpleXray/${BuildConfig.VERSION_NAME}",
            )
            .forEach { (label, value) ->
                edit(label)
                compose.onNode(hasSetTextAction()).assertTextEquals(value)
                click("Отмена")
            }
        edit("Модель устройства")
        compose.onNode(hasSetTextAction()).performTextReplacement("Chosen model")
        click("Сохранить")
        edit("Модель устройства")
        compose.onNode(hasSetTextAction()).assertTextEquals("Chosen model")
        compose.onNode(hasSetTextAction()).performTextClearance()
        click("Сохранить")
        edit("Модель устройства")
        compose.onNode(hasSetTextAction()).assertTextEquals(Build.MODEL)
        click("Отмена")
        click("Закрыть")
        compose.onNodeWithText("HTTP-заголовки").assertDoesNotExist()
    }
}
