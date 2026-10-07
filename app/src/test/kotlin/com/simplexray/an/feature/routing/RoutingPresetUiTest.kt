package com.simplexray.an.feature.routing

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.simplexray.an.feature.routing.model.RoutingPreset
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.routing.ui.RoutingPresetMenu
import com.simplexray.an.feature.routing.ui.RoutingPresetPreviewDialog
import com.simplexray.an.feature.subscriptions.ui.AddSubscriptionDialog
import com.simplexray.an.ui.navigation.ImmediateNavHost
import com.simplexray.an.ui.navigation.ROUTE_SETTINGS
import com.simplexray.an.ui.scaffold.AppScaffold
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h900dp", application = Application::class)
class RoutingPresetUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun presetMenuIsAvailableOnlyOnRoutingDetail() {
        lateinit var controller: NavHostController
        compose.setContent {
            MaterialTheme {
                controller = rememberNavController()
                ImmediateNavHost(
                    controller,
                    ROUTE_SETTINGS,
                    listOf(ROUTE_SETTINGS, "settings-detail/{section}"),
                    chrome = { entry, body ->
                        AppScaffold(
                            controller,
                            entry,
                            remember { SnackbarHostState() },
                            {},
                            {},
                            {},
                        ) {
                            Column { body() }
                        }
                    },
                ) {
                    Text("Содержимое")
                }
            }
        }
        compose.onNodeWithText("Пресет").assertDoesNotExist()
        compose.runOnIdle { controller.navigate("settings-detail/" + Uri.encode("Роутинг")) }
        compose.onNodeWithText("Пресет").performClick()
        compose.onNodeWithText("Импорт из буфера").assertExists()
        compose.onNodeWithText("Экспорт в буфер").performClick()
        compose.runOnIdle { controller.navigate("settings-detail/" + Uri.encode("DNS")) }
        compose.onNodeWithText("Пресет").assertDoesNotExist()
    }

    @Test
    fun menuActionsSelectTheirOwnImportOrExportHandler() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                RoutingPresetMenu(
                    onImport = { actions += "import" },
                    onExport = { actions += "export" },
                )
            }
        }
        listOf("Импорт из буфера", "Экспорт в буфер").forEach {
            compose.onNodeWithText("Пресет").performClick()
            compose.onNodeWithText(it).performClick()
        }
        compose.runOnIdle { assertEquals(listOf("import", "export"), actions) }
    }

    @Test
    fun previewWaitsForExplicitApplyAndCancelDoesNotApply() {
        var applied = 0
        var canceled = 0
        compose.setContent {
            MaterialTheme {
                RoutingPresetPreviewDialog(
                    preset =
                        RoutingPreset(
                            RoutingSettings(),
                            "https://example.com/geoip.dat",
                            "https://example.com/geosite.dat",
                        ),
                    onDismiss = { canceled++ },
                    onApply = { applied++ },
                )
            }
        }
        compose.onNodeWithText("https://example.com/geoip.dat").assertExists()
        compose.onNodeWithText("https://example.com/geosite.dat").assertExists()
        compose.runOnIdle { assertEquals(0, applied) }
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle {
            assertEquals(1, canceled)
            assertEquals(0, applied)
        }
        compose.onNodeWithText("Заменить").performClick()
        compose.runOnIdle { assertEquals(1, applied) }
    }

    @Test
    fun subscriptionDialogAcceptsSelfContainedPresetLink() {
        val link = "SimpleXray://Routing/eyJmb3JtYXQiOiJzaW1wbGV4cmF5LXJvdXRpbmcifQ"
        var imported = ""
        compose.setContent {
            MaterialTheme {
                AddSubscriptionDialog(
                    onDismiss = {},
                    onConfirm = { _, value -> imported = value },
                    initialUrl = link,
                )
            }
        }
        compose.onNodeWithText("Добавить").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(link, imported) }
    }

    @Test
    fun busySubscriptionImportCannotBeSubmittedOrCanceled() {
        compose.setContent {
            MaterialTheme {
                AddSubscriptionDialog(
                    onDismiss = {},
                    onConfirm = { _, _ -> },
                    initialUrl = "https://example.com/subscription",
                    busy = true,
                )
            }
        }
        compose.onNodeWithText("Отмена").assertIsNotEnabled()
        compose.onNodeWithText("Загрузка…").assertIsNotEnabled()
        compose.onNodeWithText("Вставить").assertIsNotEnabled()
    }

    @Test
    fun busyPreviewDisablesReplacementAndCancellation() {
        compose.setContent {
            MaterialTheme {
                RoutingPresetPreviewDialog(
                    preset =
                        RoutingPreset(
                            RoutingSettings(),
                            "https://example.com/geoip.dat",
                            "https://example.com/geosite.dat",
                        ),
                    onDismiss = {},
                    onApply = {},
                    busy = true,
                )
            }
        }
        compose.onNodeWithText("Отмена").assertIsNotEnabled()
        compose.onNodeWithText("Сохранение…").assertIsNotEnabled()
    }

    @Test
    fun editingSubscriptionDoesNotSaveAPresetAsItsRefreshUrl() {
        compose.setContent {
            MaterialTheme {
                AddSubscriptionDialog(
                    onDismiss = {},
                    onConfirm = { _, _ -> },
                    initialUrl = "simplexray://routing/preset",
                    editing = true,
                )
            }
        }
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
    }
}
