package com.simplexray.an.app.ui

import android.Manifest
import android.app.Application
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationPermissionTest {
    @get:Rule val compose = createComposeRule()
    private val registry = PermissionRegistry()
    private var continued = 0

    @Before
    fun denyInitially() {
        shadowOf(RuntimeEnvironment.getApplication())
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun show(): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides
                    object : ActivityResultRegistryOwner {
                        override val activityResultRegistry = registry
                    }
            ) {
                val start = rememberNotificationPermissionRequest { continued++ }
                Button(onClick = start) { Text("Connect") }
            }
        }
        return restoration
    }

    @Test
    fun grantRequestsNotificationPermissionBeforeContinuing() {
        show()
        compose.runOnIdle { assertEquals(0, registry.requests) }
        compose.onNodeWithText("Connect").performClick()
        compose.runOnIdle {
            assertEquals(1, registry.requests)
            assertEquals(Manifest.permission.POST_NOTIFICATIONS, registry.permission)
            assertEquals(0, continued)
            registry.dispatchResult(registry.code, true)
        }
        compose.runOnIdle { assertEquals(1, continued) }
    }

    @Test
    fun refusalStillContinuesConnection() {
        show()
        compose.onNodeWithText("Connect").performClick()
        compose.runOnIdle { registry.dispatchResult(registry.code, false) }
        compose.runOnIdle { assertEquals(1, continued) }
    }

    @Test
    fun alreadyGrantedPermissionContinuesWithoutPrompt() {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show()
        compose.onNodeWithText("Connect").performClick()
        compose.runOnIdle {
            assertEquals(0, registry.requests)
            assertEquals(1, continued)
        }
    }

    @Test
    fun pendingRequestSurvivesStateRestorationWithoutDuplicatePrompt() {
        val restoration = show()
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("Connect").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Connect").performClick()
        compose.runOnIdle {
            assertEquals(1, registry.requests)
            registry.dispatchResult(registry.code, false)
        }
        compose.runOnIdle { assertEquals(1, continued) }
    }

    private class PermissionRegistry : ActivityResultRegistry() {
        var requests = 0
        var code = 0
        var permission: String? = null

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            assertTrue(contract is ActivityResultContracts.RequestPermission)
            requests++
            code = requestCode
            permission = input as String
        }
    }
}
