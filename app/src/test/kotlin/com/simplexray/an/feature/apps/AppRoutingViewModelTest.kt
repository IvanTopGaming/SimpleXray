package com.simplexray.an.feature.apps

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModelStore
import com.simplexray.an.feature.apps.model.AppRoutingMode
import com.simplexray.an.feature.apps.state.AppListViewModel
import com.simplexray.an.prefs.Preferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppRoutingViewModelTest {
    private val app
        get() = RuntimeEnvironment.getApplication()

    private val prefs
        get() = Preferences(app)

    private val clipboard
        get() = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun withModel(action: (AppListViewModel) -> Unit) {
        val store = ViewModelStore()
        val model = AppListViewModel(app)
        store.put("apps", model)
        try {
            action(model)
        } finally {
            store.clear()
        }
    }

    @Test
    fun malformedImportLeavesModeAndSelectedPackagesUntouched() {
        prefs.apps = setOf("com.example.saved")
        prefs.appRoutingMode = AppRoutingMode.EXCLUDE
        withModel { model ->
            clipboard.setPrimaryClip(
                ClipData.newPlainText("bad", "false\ncom.example.good\ninvalid package")
            )
            model.importAppsFromClipboard(app)
            assertEquals(AppRoutingMode.EXCLUDE, prefs.appRoutingMode)
            assertEquals(AppRoutingMode.EXCLUDE, model.appRoutingMode)
            assertEquals(setOf("com.example.saved"), prefs.apps)
        }
    }

    @Test
    fun switchingAllAndBackPreservesSelectionAndClipboardBeforeAppsFinishLoading() {
        prefs.apps = setOf("com.example.saved")
        prefs.appRoutingMode = AppRoutingMode.INCLUDE
        withModel { model ->
            model.onAppRoutingModeChange(AppRoutingMode.ALL)
            model.selectAll()
            model.inverseSelection()
            model.exportAppsToClipboard(app)
            assertEquals(
                "all\ncom.example.saved",
                clipboard.primaryClip!!.getItemAt(0).text.toString(),
            )
            assertEquals(setOf("com.example.saved"), prefs.apps)
            model.onAppRoutingModeChange(AppRoutingMode.INCLUDE)
            assertEquals(AppRoutingMode.INCLUDE, model.appRoutingMode)
            assertEquals(setOf("com.example.saved"), prefs.apps)
        }
    }

    @Test
    fun allImportPreservesPackagesForReturningToInclude() {
        withModel { model ->
            clipboard.setPrimaryClip(ClipData.newPlainText("apps", "all\ncom.example.saved"))
            model.importAppsFromClipboard(app)
            assertEquals(AppRoutingMode.ALL, prefs.appRoutingMode)
            assertEquals(setOf("com.example.saved"), prefs.apps)
            model.onAppRoutingModeChange(AppRoutingMode.INCLUDE)
            assertEquals(setOf("com.example.saved"), prefs.apps)
        }
    }
}
