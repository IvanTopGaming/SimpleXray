package com.simplexray.an.feature.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.simplexray.an.app.state.MainViewModel
import com.simplexray.an.feature.settings.ui.AppUpdateDialog
import com.simplexray.an.support.HostCoreVersionRead
import com.simplexray.an.ui.theme.SimpleXrayTheme
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    qualifiers = "ru-rRU-w412dp-h900dp",
    application = Application::class,
    shadows = [HostCoreVersionRead::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppUpdateDialogTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()

    @After
    fun clearModel() {
        store.clear()
    }

    @Test
    fun availableUpdateShowsVersionAndDownloadOrCancelActions() {
        val model = MainViewModel(RuntimeEnvironment.getApplication())
        store.put("main", model)
        compose.setContent {
            SimpleXrayTheme(dark = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppUpdateDialog(model, "1.10.18")
                }
            }
        }
        compose.onNodeWithText("Доступна новая версия").assertIsDisplayed()
        compose
            .onNodeWithText("Доступна новая версия (1.10.18). Хотите скачать?")
            .assertIsDisplayed()
        compose.onNodeWithText("Скачать").assertIsEnabled()
        compose.onNodeWithText("Отмена").assertIsEnabled()
        System.getenv("SIMPLEXRAY_UPDATE_PREVIEW")?.let { path ->
            val file = File(path)
            file.parentFile?.mkdirs()
            compose.runOnIdle {
                val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }
}
