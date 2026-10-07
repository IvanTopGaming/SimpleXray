package com.simplexray.an.feature.apps

import android.app.Application
import android.content.pm.PackageManager
import android.net.VpnService
import com.simplexray.an.BuildConfig
import com.simplexray.an.feature.apps.model.AppRoutingMode
import com.simplexray.an.feature.apps.model.AppRoutingPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [RecordingVpnBuilder::class])
class AppRoutingPolicyTest {
    private fun builder() = Robolectric.buildService(VpnService::class.java).get().Builder()

    @Test
    fun allIgnoresRetainedSelectionAndAlwaysExcludesSelf() {
        val builder = builder()
        AppRoutingPolicy.configure(builder, AppRoutingMode.ALL, setOf("com.example.selected"))
        val calls = Shadow.extract<RecordingVpnBuilder>(builder)
        assertEquals(emptyList<String>(), calls.allowed)
        assertEquals(listOf(BuildConfig.APPLICATION_ID), calls.disallowed)
    }

    @Test
    fun exclusionSkipsMissingPackagesAndIncludesSelfExactlyOnce() {
        val builder = builder()
        AppRoutingPolicy.configure(
            builder,
            AppRoutingMode.EXCLUDE,
            setOf("com.example.selected", "missing.app", BuildConfig.APPLICATION_ID, null),
        )
        val calls = Shadow.extract<RecordingVpnBuilder>(builder)
        assertEquals(emptyList<String>(), calls.allowed)
        assertEquals(
            setOf("com.example.selected", BuildConfig.APPLICATION_ID),
            calls.disallowed.toSet(),
        )
        assertEquals(2, calls.disallowed.size)
    }

    @Test
    fun includeRequiresAtLeastOneInstalledExternalApplication() {
        listOf(emptySet(), setOf("missing.app"), setOf(BuildConfig.APPLICATION_ID), setOf(null))
            .forEach { selected ->
                val error =
                    assertThrows(IllegalArgumentException::class.java) {
                        AppRoutingPolicy.configure(builder(), AppRoutingMode.INCLUDE, selected)
                    }
                assertTrue(error.message.orEmpty().contains("приложен"))
            }
        val builder = builder()
        AppRoutingPolicy.configure(
            builder,
            AppRoutingMode.INCLUDE,
            setOf("missing.app", "com.example.selected", BuildConfig.APPLICATION_ID),
        )
        val calls = Shadow.extract<RecordingVpnBuilder>(builder)
        assertEquals(listOf("com.example.selected"), calls.allowed)
        assertTrue(calls.disallowed.isEmpty())
    }
}

@Implements(VpnService.Builder::class)
class RecordingVpnBuilder {
    @RealObject lateinit var builder: VpnService.Builder
    val allowed = mutableListOf<String>()
    val disallowed = mutableListOf<String>()

    @Implementation
    fun addAllowedApplication(name: String): VpnService.Builder {
        if (name == "missing.app") throw PackageManager.NameNotFoundException(name)
        allowed += name
        return builder
    }

    @Implementation
    fun addDisallowedApplication(name: String): VpnService.Builder {
        if (name == "missing.app") throw PackageManager.NameNotFoundException(name)
        disallowed += name
        return builder
    }
}
