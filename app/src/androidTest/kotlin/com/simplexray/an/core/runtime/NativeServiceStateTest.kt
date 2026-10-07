package com.simplexray.an.core.runtime

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import com.simplexray.an.activity.MainActivity
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.service.TProxyService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeServiceStateTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun invalidCoreNeverAnnouncesSuccessfulStart() {
        val app = compose.activity.application
        val prefs = Preferences(app)
        val oldPath = prefs.selectedConfigPath
        val oldMode = prefs.disableVpn
        val file = File(app.filesDir, "test-invalid-core.json")
        file.writeText("""{"log":{},"outbounds":[{"protocol":"not-a-protocol"}]}""")
        val started = AtomicBoolean(false)
        val stopped = CountDownLatch(1)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == TProxyService.ACTION_START) started.set(true)
                    if (intent.action == TProxyService.ACTION_STOP) stopped.countDown()
                }
            }
        app.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(TProxyService.ACTION_START)
                addAction(TProxyService.ACTION_STOP)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        try {
            prefs.disableVpn = true
            prefs.selectedConfigPath = file.absolutePath
            app.startService(
                Intent(app, TProxyService::class.java).setAction(TProxyService.ACTION_START)
            )
            assertTrue("Invalid core should terminate", stopped.await(10, TimeUnit.SECONDS))
            assertFalse("A failed core must not announce connected", started.get())
        } finally {
            app.unregisterReceiver(receiver)
            app.stopService(Intent(app, TProxyService::class.java))
            prefs.selectedConfigPath = oldPath
            prefs.disableVpn = oldMode
            file.delete()
        }
    }

    @Test
    fun stopIsObservedWithoutMainScreenComposition() = runBlocking {
        val app = compose.activity.application as Application
        val file = File(app.filesDir, "test-observer.json")
        file.writeText("""{"outbounds":[{"protocol":"freedom"}]}""")
        val model = MainViewModel(app)
        val store = ViewModelStore().apply { put("test", model) }
        try {
            withTimeout(5000) {
                model.configFiles.first { files -> files.any { it.name == file.name } }
            }
            model.setServiceEnabled(true)
            app.sendBroadcast(Intent(TProxyService.ACTION_STOP).setPackage(app.packageName))
            withTimeout(3000) { model.isServiceEnabled.first { !it } }
            assertFalse(model.isServiceEnabled.value)
        } finally {
            store.clear()
            file.delete()
        }
    }
}
