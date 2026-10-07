package com.simplexray.an.feature.kernel

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.app.state.MainViewUiEvent
import com.simplexray.an.support.HostCoreVersionRead
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [HostCoreVersionRead::class])
class KernelProbeFailureTest {
    @Test
    fun invalidKernelSnapshotDoesNotCrashOrStartServerChecks() {
        val model = MainViewModel(RuntimeEnvironment.getApplication())
        val store = ViewModelStore().apply { put("main", model) }
        try {
            model.prefs.kernelSettingsJson = "broken"
            model.checkServers(emptyList())
            assertEquals("broken", model.prefs.kernelSettingsJson)
            assertFalse(model.isServiceEnabled.value)
            assertFalse(model.serverChecks.value.running)
            val error = runBlocking {
                withTimeout(1000) {
                    model.uiEvent.filterIsInstance<MainViewUiEvent.ShowSnackbar>().first()
                }
            }
            assertTrue(error.message.contains("ядра"))
        } finally {
            store.clear()
        }
    }
}
