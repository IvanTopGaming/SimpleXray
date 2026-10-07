package com.simplexray.an.feature.apps

import android.app.Application
import com.simplexray.an.feature.apps.model.AppRoutingMode
import com.simplexray.an.prefs.Preferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppRoutingModeTest {
    private val prefs
        get() = Preferences(RuntimeEnvironment.getApplication())

    @Test
    fun missingModeMigratesLegacyEmptySelectionToAll() {
        prefs.bypassSelectedApps = false
        assertEquals(AppRoutingMode.ALL, prefs.appRoutingMode)
        prefs.bypassSelectedApps = true
        assertEquals(AppRoutingMode.ALL, prefs.appRoutingMode)
    }

    @Test
    fun missingModeMigratesLegacyNonemptySelectionToItsOriginalPolicy() {
        prefs.apps = setOf("com.example.selected")
        prefs.bypassSelectedApps = true
        assertEquals(AppRoutingMode.EXCLUDE, prefs.appRoutingMode)
        prefs.bypassSelectedApps = false
        assertEquals(AppRoutingMode.INCLUDE, prefs.appRoutingMode)
    }

    @Test
    fun allModeRetainsSelectionAndSurvivesPreferenceReload() {
        val selected = setOf("com.example.selected", "com.example.other")
        prefs.apps = selected
        prefs.appRoutingMode = AppRoutingMode.EXCLUDE
        assertTrue(prefs.bypassSelectedApps)
        prefs.appRoutingMode = AppRoutingMode.ALL
        assertEquals(AppRoutingMode.ALL, prefs.appRoutingMode)
        assertEquals(selected, prefs.apps)
        prefs.appRoutingMode = AppRoutingMode.INCLUDE
        assertFalse(prefs.bypassSelectedApps)
        assertEquals(selected, prefs.apps)
        assertEquals(AppRoutingMode.INCLUDE, prefs.appRoutingMode)
    }
}
