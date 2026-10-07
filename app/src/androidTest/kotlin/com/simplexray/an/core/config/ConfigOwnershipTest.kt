package com.simplexray.an.core.config

import android.app.Application
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.platform.app.InstrumentationRegistry
import com.simplexray.an.core.files.FileManager
import com.simplexray.an.feature.servers.state.ConfigEditViewModel
import com.simplexray.an.feature.subscriptions.model.Subscription
import com.simplexray.an.prefs.Preferences
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ConfigOwnershipTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Test
    fun importingSameNameDoesNotOverwriteExistingServer() = runBlocking {
        val original = File(app.filesDir, "test-collision.json")
        original.writeText("original")
        var imported: File? = null
        try {
            val path =
                FileManager(app, Preferences(app))
                    .importConfigFromContent(
                        "vless://11111111-1111-1111-1111-111111111111@localhost:443?security=none#test-collision"
                    )
            imported = path?.let(::File)
            assertNotNull(path)
            assertEquals("original", original.readText())
            assertNotEquals(original.absolutePath, path)
        } finally {
            original.delete()
            imported?.delete()
        }
    }

    @Test
    fun subscriptionOwnershipIsEnforcedWhenSaving() = runBlocking {
        val file = File(app.filesDir, "test-owned.json")
        val original = """{"outbounds":[{"protocol":"freedom"}]}"""
        val prefs = Preferences(app)
        file.writeText(original)
        prefs.subscriptions =
            listOf(
                Subscription(
                    "test-owned",
                    "Provider",
                    "https://example.invalid",
                    0,
                    listOf(file.name),
                )
            )
        try {
            val model = ConfigEditViewModel(app, file.absolutePath, prefs)
            withTimeout(5000) { model.configTextFieldValue.first { it.text.isNotEmpty() } }
            model.onConfigContentChange(
                TextFieldValue("""{"log":{},"outbounds":[{"protocol":"blackhole"}]}""")
            )
            val result = async(start = CoroutineStart.UNDISPATCHED) { model.uiEvent.first() }
            model.saveConfigFile()
            withTimeout(5000) { result.await() }
            assertEquals(original, file.readText())
        } finally {
            prefs.subscriptions = emptyList()
            file.delete()
        }
    }

    @Test
    fun validConfigDoesNotRequireOptionalLogSection() {
        val formatted =
            com.simplexray.an.core.config.ConfigUtils.formatConfigContent(
                """{"outbounds":[{"protocol":"freedom"}]}"""
            )
        assertTrue(formatted.contains("freedom"))
    }
}
