package com.simplexray.an.feature.servers

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.prefs.Preferences
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeConfigRefreshTest {
    @Test
    fun refreshUpdatesDetailsAtTheSamePathAndPreservesSelection() = runBlocking {
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val prefs = Preferences(app)
        val oldSelected = prefs.selectedConfigPath
        val oldOrder = prefs.configFilesOrder
        val file = File(app.filesDir, "tab-refresh-${System.nanoTime()}.json")
        file.writeText("""{"outbounds":[{"tag":"proxy","protocol":"vless"}]}""")
        prefs.selectedConfigPath = file.absolutePath
        val model = MainViewModel(app)
        val store = ViewModelStore().apply { put("refresh", model) }
        try {
            model.refreshConfigFileList().join()
            assertEquals("VLESS", model.serverDetails.value[file]?.protocol)
            assertEquals(file, model.selectedConfigFile.value)

            file.writeText("""{"outbounds":[{"tag":"proxy","protocol":"trojan"}]}""")
            model.refreshConfigFileList().join()
            assertEquals("TROJAN", model.serverDetails.value[file]?.protocol)
            assertEquals(file, model.selectedConfigFile.value)
            assertEquals(file.absolutePath, prefs.selectedConfigPath)

            file.delete()
            model.refreshConfigFileList().join()
            assertFalse(model.serverDetails.value.containsKey(file))
            assertFalse(model.configFiles.value.contains(file))
            assertNull(model.selectedConfigFile.value)
        } finally {
            store.clear()
            file.delete()
            prefs.selectedConfigPath = oldSelected
            prefs.configFilesOrder = oldOrder
        }
    }
}
