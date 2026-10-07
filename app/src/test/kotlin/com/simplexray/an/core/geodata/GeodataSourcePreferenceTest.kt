package com.simplexray.an.core.geodata

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.support.HostCoreVersionRead
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [HostCoreVersionRead::class])
class GeodataSourcePreferenceTest {
    @Test fun failedDownloadPreservesCustomSource() = checkUnsuccessfulDownload(false)

    @Test fun cancelledDownloadPreservesCustomSource() = checkUnsuccessfulDownload(true)

    private fun checkUnsuccessfulDownload(cancel: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        val model = MainViewModel(app)
        val store = ViewModelStore().apply { put("main", model) }
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val received = CountDownLatch(1)
        val respond = CountDownLatch(1)
        val original = "https://custom.example/geoip.dat"
        model.prefs.geoipUrl = original
        val worker = executor.submit {
            server.soTimeout = 5000
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) {}
                received.countDown()
                if (respond.await(5, TimeUnit.SECONDS)) {
                    runCatching {
                        socket
                            .getOutputStream()
                            .write(
                                "HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .toByteArray()
                            )
                    }
                }
            }
        }
        try {
            model.downloadRuleFile("http://127.0.0.1:${server.localPort}/geoip.dat", "geoip.dat")
            assertTrue(received.await(5, TimeUnit.SECONDS))
            assertEquals(
                "Source must remain unchanged while downloading",
                original,
                model.prefs.geoipUrl,
            )
            if (cancel) {
                model.cancelDownload("geoip.dat")
                shadowOf(Looper.getMainLooper()).idle()
            }
            respond.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (model.geoipDownloadProgress.value != null && System.nanoTime() < deadline) Thread
                .sleep(10)
            assertNull(model.geoipDownloadProgress.value)
            assertEquals(original, model.prefs.geoipUrl)
        } finally {
            respond.countDown()
            store.clear()
            shadowOf(Looper.getMainLooper()).idle()
            server.close()
            worker.cancel(true)
            executor.shutdownNow()
        }
    }
}
