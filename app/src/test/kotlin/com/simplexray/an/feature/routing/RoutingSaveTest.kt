package com.simplexray.an.feature.routing

import com.simplexray.an.core.runtime.validation.CoreConfigValidator
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingRule
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.routing.state.RoutingEditor
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RoutingSaveTest {
    @Test
    fun validatorAllowsSlowSuccessfulChecks() = runBlocking {
        val dir = Files.createTempDirectory("routing-slow-success").toFile()
        val executable =
            File(dir, "core").apply {
                writeText("#!/bin/sh\nsleep 6\nexit 0\n")
                setExecutable(true)
            }
        try {
            CoreConfigValidator(executable, dir, dir).validate("{}")
            assertFalse(dir.listFiles()!!.any { it.name.endsWith(".json") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun validatorRejectsSlowInvalidChecksWithoutReportingTimeout() = runBlocking {
        val dir = Files.createTempDirectory("routing-slow-failure").toFile()
        val executable =
            File(dir, "core").apply {
                writeText("#!/bin/sh\nsleep 6\nexit 23\n")
                setExecutable(true)
            }
        try {
            try {
                CoreConfigValidator(executable, dir, dir).validate("{}")
                fail("An invalid configuration must be rejected")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("отклонил конфигурацию"))
            }
            assertFalse(dir.listFiles()!!.any { it.name.endsWith(".json") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun validatorAcceptsSuccessRejectsFailureAndCleansPrivateFiles() = runBlocking {
        val dir = Files.createTempDirectory("routing-validation").toFile()
        try {
            CoreConfigValidator(File("/usr/bin/true"), dir, dir).validate("{}")
            assertTrue(dir.listFiles()!!.isEmpty())
            try {
                CoreConfigValidator(File("/usr/bin/false"), dir, dir).validate("{}")
                fail()
            } catch (_: IllegalArgumentException) {}
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun validatorTimeoutAndCancellationTerminateChild() = runBlocking {
        val dir = Files.createTempDirectory("routing-timeout").toFile()
        val executable =
            File(dir, "core").apply {
                writeText("#!/bin/sh\necho \u0024\u0024 > '${dir.path}/pid'\nexec sleep 60\n")
                setExecutable(true)
            }
        try {
            try {
                CoreConfigValidator(executable, dir, dir, 200).validate("{}")
                fail()
            } catch (_: IllegalArgumentException) {}
            val pid = File(dir, "pid").readText().trim().toLong()
            assertFalse(File("/proc/$pid").exists())
            File(dir, "pid").delete()
            val job = launch { CoreConfigValidator(executable, dir, dir).validate("{}") }
            withTimeout(3000) { while (!File(dir, "pid").exists()) delay(10) }
            val cancelledPid = File(dir, "pid").readText().trim().toLong()
            job.cancelAndJoin()
            assertFalse(File("/proc/$cancelledPid").exists())
            assertFalse(dir.listFiles()!!.any { it.name.endsWith(".json") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun everyValidEditPersistsBeforeReturning() {
        var stored: String? = null
        val editor = RoutingEditor({ stored }, { stored = it })
        editor.edit { it.copy(defaultRoute = RouteTarget.DIRECT) }
        assertEquals(RouteTarget.DIRECT, RoutingSettings.decode(stored).defaultRoute)
        editor.edit { it.copy(bypassLan = false) }
        assertFalse(RoutingSettings.decode(stored).bypassLan)
        assertEquals(
            editor.state.value.draft,
            RoutingEditor({ stored }, { stored = it }).state.value.draft,
        )
    }

    @Test
    fun invalidEditPreservesSavedState() {
        var stored: String? = RoutingSettings().encode()
        val original = stored
        val editor = RoutingEditor({ stored }, { stored = it })
        editor.edit { it.upsert(RoutingRule(name = "Bad", values = listOf("https://example.com"))) }
        assertEquals(original, stored)
        assertTrue(editor.state.value.draft.rules.isEmpty())
        assertNotNull(editor.state.value.error)
    }

    @Test
    fun failedWriteDoesNotPresentUnsavedValuesAsSaved() {
        val original = RoutingSettings().encode()
        val editor = RoutingEditor({ original }, { throw IllegalStateException("storage") })
        editor.edit { it.copy(defaultRoute = RouteTarget.BLOCK) }
        assertEquals(RouteTarget.PROXY, editor.state.value.draft.defaultRoute)
        assertNotNull(editor.state.value.error)
    }

    @Test
    fun corruptPreferencesRequireExplicitReset() {
        var stored: String? = "broken"
        val editor = RoutingEditor({ stored }, { stored = it })
        assertNotNull(editor.state.value.error)
        assertTrue(editor.state.value.corrupt)
        editor.edit { it.copy(defaultRoute = RouteTarget.BLOCK) }
        assertEquals("broken", stored)
        editor.reset()
        assertEquals(RoutingSettings(), RoutingSettings.decode(stored))
        assertFalse(editor.state.value.corrupt)
    }

    @Test
    fun legacyProviderModeMigratesWithoutLosingRules() {
        val rule = RoutingRule(name = "Keep", values = listOf("example.com"))
        val old = RoutingSettings(enabled = false, rules = listOf(rule)).encode()
        val migrated = RoutingSettings.decode(old)
        assertTrue(migrated.enabled)
        assertEquals(listOf(rule), migrated.rules)
    }
}
