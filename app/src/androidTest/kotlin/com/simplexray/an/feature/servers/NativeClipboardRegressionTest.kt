package com.simplexray.an.feature.servers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.simplexray.an.feature.servers.ui.ImportServerDialog
import com.simplexray.an.feature.subscriptions.ui.AddSubscriptionDialog
import com.simplexray.an.ui.theme.SimpleXrayTheme
import org.junit.Rule
import org.junit.Test

class NativeClipboardRegressionTest {
    @get:Rule val compose = createComposeRule()

    private fun emptyClipboard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.runOnUiThread { manager.setPrimaryClip(ClipData.newPlainText("", "")) }
    }

    @Test
    fun emptyPasteDoesNotEraseSubscriptionUrl() {
        compose.setContent {
            SimpleXrayTheme(false) {
                AddSubscriptionDialog(
                    onDismiss = {},
                    onConfirm = { _, _ -> },
                    initialUrl = "https://example.invalid/keep",
                )
            }
        }
        emptyClipboard()
        compose.onNodeWithContentDescription("Вставить ссылку подписки из буфера").performClick()
        compose
            .onNodeWithContentDescription("Ссылка подписки")
            .assertTextEquals("https://example.invalid/keep")
    }

    @Test
    fun emptyPasteDoesNotEraseManualServerLink() {
        compose.setContent {
            SimpleXrayTheme(false) {
                ImportServerDialog("vless://keep", onDismiss = {}, onImport = { _, _ -> true })
            }
        }
        emptyClipboard()
        compose.onNodeWithContentDescription("Вставить ссылку из буфера").performClick()
        compose.onNodeWithContentDescription("Ссылка на сервер").assertTextEquals("vless://keep")
    }
}
