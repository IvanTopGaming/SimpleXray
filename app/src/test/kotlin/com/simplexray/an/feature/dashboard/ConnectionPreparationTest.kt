package com.simplexray.an.feature.dashboard

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.service.TProxyService
import com.simplexray.an.support.HostCoreVersionRead
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [HostCoreVersionRead::class])
class ConnectionPreparationTest {
    @Test
    fun downloadProgressAndFailureRestoreUsableConnectionControls() {
        val app = RuntimeEnvironment.getApplication()
        val store = ViewModelStore()
        val model = MainViewModel(app)
        store.put("main", model)
        fun progress(error: Boolean = false) {
            app.sendBroadcast(
                Intent(TProxyService.ACTION_PREPARATION)
                    .setPackage(app.packageName)
                    .putExtra(TProxyService.EXTRA_PREPARATION, "Загрузка geoip.dat")
                    .putExtra(TProxyService.EXTRA_PREPARATION_ERROR, error)
            )
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            progress()
            assertEquals("Загрузка geoip.dat", model.connectionPreparation.value)
            assertFalse(model.controlMenuClickable.value)
            progress(error = true)
            assertNull(model.connectionPreparation.value)
            assertTrue(model.controlMenuClickable.value)
            progress()
            model.stopTProxyService()
            assertNull(model.connectionPreparation.value)
            app.sendBroadcast(Intent(TProxyService.ACTION_STOP).setPackage(app.packageName))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(model.controlMenuClickable.value)
            assertFalse(model.isServiceEnabled.value)
        } finally {
            store.clear()
        }
    }
}
