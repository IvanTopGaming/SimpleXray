package com.simplexray.an.feature.servers

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.core.files.FileManager
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.support.HostCoreVersionRead
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [HostCoreVersionRead::class])
class ServerImportNameTest {
    private val link =
        "vless://00000000-0000-0000-0000-000000000001@edge.example:443?security=none#Parsed"

    @Test
    fun customNameIsSanitizedAndDuplicateImportsPreserveBothFiles() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val files = FileManager(app, Preferences(app))
        val first = File(requireNotNull(files.importConfigFromContent(link, "  ../Office:VPN  ")))
        val original = first.readText()
        val second = File(requireNotNull(files.importConfigFromContent(link, "  ../Office:VPN  ")))

        assertEquals("_Office_VPN.json", first.name)
        assertEquals("_Office_VPN (2).json", second.name)
        assertEquals(app.filesDir.canonicalFile, first.parentFile.canonicalFile)
        assertEquals(original, first.readText())
        assertEquals(original, second.readText())
        assertFalse(File(app.filesDir, "Parsed.json").exists())
    }

    @Test
    fun blankNameKeepsParsedName() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val files = FileManager(app, Preferences(app))

        val imported = File(requireNotNull(files.importConfigFromContent(link, " \t\n ")))

        assertEquals("Parsed.json", imported.name)
        assertTrue(imported.readText().contains("edge.example"))
    }

    @Test
    fun serverImportForwardsCustomNameAndPresetIgnoresIt() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val model = MainViewModel(app)
        val store = ViewModelStore().apply { put("main", model) }
        try {
            assertTrue(model.importServer(link, "Chosen"))
            val server = File(app.filesDir, "Chosen.json")
            assertTrue(server.exists())
            val before = server.readText()
            val preset = model.prefs.readRoutingPreset()

            assertTrue(model.importServer(preset.encodeLink(), "Ignored"))

            assertEquals(preset, model.pendingRoutingPreset.value)
            assertEquals(before, server.readText())
            assertFalse(File(app.filesDir, "Ignored.json").exists())
            assertEquals(
                listOf("Chosen.json"),
                app.filesDir.listFiles().orEmpty().filter { it.extension == "json" }.map { it.name },
            )
        } finally {
            store.clear()
        }
    }
}
