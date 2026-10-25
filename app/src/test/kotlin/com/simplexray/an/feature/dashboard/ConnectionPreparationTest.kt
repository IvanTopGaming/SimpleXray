package com.simplexray.an.feature.dashboard

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.core.geodata.GeodataProgress
import com.simplexray.an.feature.dashboard.model.ConnectionPreparation
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
        fun progress(error: Boolean = false, download: Boolean = false) {
            val intent =
                Intent(TProxyService.ACTION_PREPARATION)
                    .setPackage(app.packageName)
                    .putExtra(TProxyService.EXTRA_PREPARATION, "Загрузка баз")
                    .putExtra(TProxyService.EXTRA_PREPARATION_ERROR, error)
            if (download) {
                intent
                    .putExtra(TProxyService.EXTRA_DOWNLOAD_FILE, "geoip.dat")
                    .putExtra(TProxyService.EXTRA_DOWNLOAD_BYTES, 512L)
                    .putExtra(TProxyService.EXTRA_DOWNLOAD_TOTAL, 2048L)
            }
            app.sendBroadcast(intent)
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            progress()
            assertEquals(ConnectionPreparation("Загрузка баз"), model.connectionPreparation.value)
            assertFalse(model.controlMenuClickable.value)
            progress(download = true)
            assertEquals(
                ConnectionPreparation("Загрузка баз", GeodataProgress("geoip.dat", 512L, 2048L)),
                model.connectionPreparation.value,
            )
            assertEquals(.25f, model.connectionPreparation.value?.download?.fraction)
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
