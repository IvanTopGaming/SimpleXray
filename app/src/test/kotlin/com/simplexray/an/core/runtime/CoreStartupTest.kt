package com.simplexray.an.core.runtime

import android.app.Application
import android.content.Intent
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingRule
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.routing.model.RuleKind
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.service.TProxyService
import com.simplexray.an.support.HostProxyService
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [HostProxyService::class])
class CoreStartupTest {
    private fun withService(script: String, block: (TProxyService, File) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val dir = Files.createTempDirectory("core-startup").toFile()
        val originalLibraryDir = app.applicationInfo.nativeLibraryDir
        app.applicationInfo.nativeLibraryDir = dir.path
        val executable = File(dir, "libxray.so")
        executable.writeText(
            "#!/bin/sh\necho \u0024\u0024 >> '${dir.path}/pids'\n" +
                "printf '%s\\n' \"\u0024*\" >> '${dir.path}/calls'\n" +
                script
        )
        executable.setExecutable(true)
        val source = File(dir, "server.json")
        source.writeText(
            """{"outbounds":[{"protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":9}]}}]}"""
        )
        val prefs = Preferences(app)
        prefs.disableVpn = true
        prefs.selectedConfigPath = source.path
        prefs.socksPort = ServerSocket(0).use { it.localPort }
        val controller = Robolectric.buildService(TProxyService::class.java).create()
        val service = controller.get()
        try {
            block(service, dir)
        } finally {
            service.onStartCommand(Intent(TProxyService.ACTION_DISCONNECT), 0, 99)
            await { !prefs.enable }
            await {
                File(dir, "pids")
                    .takeIf { it.exists() }
                    ?.readLines()
                    .orEmpty()
                    .all { !File("/proc/${it.trim().toLong()}").exists() }
            }
            app.applicationInfo.nativeLibraryDir = originalLibraryDir
            dir.deleteRecursively()
        }
    }

    private fun await(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("Condition did not become true within $timeoutMs ms", condition())
    }

    private fun broadcasts() = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents

    private fun errors() =
        broadcasts().filter {
            it.action == TProxyService.ACTION_PREPARATION &&
                it.getBooleanExtra(TProxyService.EXTRA_PREPARATION_ERROR, false)
        }

    private fun hostCore(): File {
        val core = System.getenv("SIMPLEXRAY_TEST_CORE")?.let(::File)
        assumeTrue("Host Xray must be explicitly supplied", core?.canExecute() == true)
        return requireNotNull(core)
    }

    @Test
    fun ordinaryConnectionStartsOnceAndAllowsSlowCoreReadiness() {
        val core = hostCore()
        withService("sleep 11\nexec '${core.path}' \"\u0024@\"\n") { service, dir ->
            service.onStartCommand(Intent(TProxyService.ACTION_CONNECT), 0, 1)
            await(18000) { broadcasts().any { it.action == TProxyService.ACTION_START } }
            assertEquals(listOf("run -c stdin:"), File(dir, "calls").readLines())
            assertTrue(errors().isEmpty())
            assertTrue(Preferences(service).enable)
        }
    }

    @Test
    fun failedCoreStartupReportsErrorAndRetainsDetailsInLog() {
        withService(
            "if [ \"\u00242\" = '-test' ]; then exit 0; fi\n" +
                "cat >/dev/null\nprintf 'Failed to start: invalid fixture\\n'\nexit 23\n"
        ) { service, _ ->
            service.onStartCommand(Intent(TProxyService.ACTION_CONNECT), 0, 1)
            await { broadcasts().any { it.action == TProxyService.ACTION_STOP } }
            assertEquals(1, errors().size)
            assertTrue(File(service.filesDir, "app_log.txt").readText().contains("invalid fixture"))
            assertFalse(Preferences(service).enable)
            assertFalse(broadcasts().any { it.action == TProxyService.ACTION_START })
        }
    }

    @Test
    fun reloadStartsReplacementWithoutPrevalidation() {
        val core = hostCore()
        withService(
            "if [ \"\u00242\" = '-test' ]; then exit 23; fi\nexec '${core.path}' \"\u0024@\"\n"
        ) { service, dir ->
            service.onStartCommand(Intent(TProxyService.ACTION_CONNECT), 0, 1)
            await { broadcasts().any { it.action == TProxyService.ACTION_START } }
            service.onStartCommand(Intent(TProxyService.ACTION_RELOAD_CONFIG), 0, 2)
            await { broadcasts().count { it.action == TProxyService.ACTION_START } == 2 }
            assertEquals(listOf("run -c stdin:", "run -c stdin:"), File(dir, "calls").readLines())
            assertTrue(errors().isEmpty())
            assertTrue(Preferences(service).enable)
            assertFalse(broadcasts().any { it.action == TProxyService.ACTION_STOP })
        }
    }

    @Test
    fun invalidReloadReportsStartupFailureAndStopsConnection() {
        val core = hostCore()
        withService("exec '${core.path}' \"\u0024@\"\n") { service, dir ->
            service.onStartCommand(Intent(TProxyService.ACTION_CONNECT), 0, 1)
            await { broadcasts().any { it.action == TProxyService.ACTION_START } }
            val source = File(dir, "server.json")
            source.writeText(source.readText().replace("\"port\":9", "\"port\":70000"))
            service.onStartCommand(Intent(TProxyService.ACTION_RELOAD_CONFIG), 0, 2)
            await { broadcasts().any { it.action == TProxyService.ACTION_STOP } }
            assertEquals(listOf("run -c stdin:", "run -c stdin:"), File(dir, "calls").readLines())
            assertEquals(1, errors().size)
            assertFalse(Preferences(service).enable)
        }
    }

    @Test
    fun runtimeLoadsAdditionalServerFromSavedBlockReference() {
        val core = hostCore()
        withService(
            "cat > server-block-runtime.json\nexec '${core.path}' run -c server-block-runtime.json\n"
        ) { service, _ ->
            val extra =
                File(service.filesDir, "Extra.json").apply {
                    writeText(
                        """{"outbounds":[{"protocol":"socks","settings":{"address":"127.0.0.1","port":12346}}]}"""
                    )
                }
            val custom =
                com.simplexray.an.feature.routing.model.RoutingBlock(
                    RouteTarget.PROXY,
                    "suffix:gemini.test",
                    "route_extra",
                    com.simplexray.an.feature.routing.model.RoutingServerRef(extra.name, "Extra"),
                )
            val blocks =
                listOf(custom) +
                    com.simplexray.an.feature.routing.model.RoutingBlocks.defaultOrder.map {
                        com.simplexray.an.feature.routing.model.RoutingBlock(it)
                    }
            Preferences(service).routingSettingsJson =
                RoutingSettings(
                        blocks = blocks,
                        rules =
                            blocks.flatMap(
                                com.simplexray.an.feature.routing.model.RoutingBlocks::parse
                            ),
                    )
                    .encode()
            try {
                service.onStartCommand(Intent(TProxyService.ACTION_CONNECT), 0, 1)
                await { broadcasts().any { it.action == TProxyService.ACTION_START } }
                val runtime =
                    com.google.gson.JsonParser.parseString(
                            File(service.filesDir, "server-block-runtime.json").readText()
                        )
                        .asJsonObject
                val tag =
                    com.simplexray.an.core.config.routing.RoutingServerTags.outbound(custom.id)
                assertTrue(
                    runtime.getAsJsonArray("outbounds").any {
                        it.asJsonObject["tag"]?.asString == tag
                    }
                )
                assertTrue(errors().isEmpty())
            } finally {
                extra.delete()
            }
        }
    }

    @Test
    fun cancellingStartupDoesNotReportFailure() {
        withService(
            "if [ \"\u00242\" = '-test' ]; then exit 0; fi\ncat >/dev/null\nexec sleep 60\n"
        ) { service, dir ->
            service.onStartCommand(Intent(TProxyService.ACTION_CONNECT), 0, 1)
            await {
                File(dir, "calls").takeIf { it.exists() }?.readText()?.contains("run -c stdin:") ==
                    true
            }
            service.onStartCommand(Intent(TProxyService.ACTION_DISCONNECT), 0, 2)
            await { broadcasts().any { it.action == TProxyService.ACTION_STOP } }
            assertTrue(errors().isEmpty())
            assertFalse(broadcasts().any { it.action == TProxyService.ACTION_START })
        }
    }

    @Test
    fun startupDeadlineStopsHungCoreAndReportsOneError() {
        withService("cat >/dev/null\nexec sleep 60\n") { service, _ ->
            service.onStartCommand(Intent(TProxyService.ACTION_CONNECT), 0, 1)
            await(35000) { broadcasts().any { it.action == TProxyService.ACTION_STOP } }
            assertEquals(1, errors().size)
            assertTrue(File(service.filesDir, "app_log.txt").readText().contains("startup timeout"))
            assertFalse(Preferences(service).enable)
            assertFalse(broadcasts().any { it.action == TProxyService.ACTION_START })
        }
    }

    @Test
    fun missingExecutableReportsStartupFailure() {
        withService("exit 0\n") { service, dir ->
            assertTrue(File(dir, "libxray.so").delete())
            service.onStartCommand(Intent(TProxyService.ACTION_CONNECT), 0, 1)
            await { broadcasts().any { it.action == TProxyService.ACTION_STOP } }
            assertEquals(1, errors().size)
            assertTrue(File(service.filesDir, "app_log.txt").readText().contains("IOException"))
            assertTrue(File(service.filesDir, "app_log.txt").readText().contains("libxray.so"))
            assertFalse(Preferences(service).enable)
        }
    }

    @Test
    fun geositeConnectionIgnoresLegacyMatcherCacheAndStartsOncePerConnection() {
        val core = hostCore()
        withService(
            "printf '%s\\n' \"\u0024XRAY_MPH_CACHE\" >> envs\nexec '${core.path}' \"\u0024@\"\n"
        ) { service, dir ->
            configureSplitDns(service)
            val legacy = File(service.cacheDir, "matcher-cache/${"a".repeat(64)}")
            legacy.mkdirs()
            File(legacy, "matcher.cache").writeText("obsolete cache")
            File(legacy, "sha256").writeText("invalid checksum")
            service.onStartCommand(Intent(TProxyService.ACTION_CONNECT), 0, 1)
            await(10000) { broadcasts().any { it.action == TProxyService.ACTION_START } }
            assertEquals(listOf("run -c stdin:"), File(dir, "calls").readLines())
            assertEquals(listOf(""), File(service.filesDir, "envs").readLines())
            assertTrue(errors().isEmpty())
            service.onStartCommand(Intent(TProxyService.ACTION_DISCONNECT), 0, 2)
            await { !Preferences(service).enable }
            val warm = Robolectric.buildService(TProxyService::class.java).create().get()
            val priorStarts = broadcasts().count { it.action == TProxyService.ACTION_START }
            try {
                warm.onStartCommand(Intent(TProxyService.ACTION_CONNECT), 0, 3)
                await(10000) {
                    broadcasts().count { it.action == TProxyService.ACTION_START } > priorStarts
                }
                assertEquals(
                    listOf("run -c stdin:", "run -c stdin:"),
                    File(dir, "calls").readLines(),
                )
                assertEquals(listOf("", ""), File(warm.filesDir, "envs").readLines())
                assertTrue(errors().isEmpty())
            } finally {
                warm.onStartCommand(Intent(TProxyService.ACTION_DISCONNECT), 0, 4)
            }
        }
    }

    @Test
    fun splitDnsStartsOnceWithoutMatcherCacheBuild() {
        val core = hostCore()
        withService(
            "if [ \"\u00241\" != run ]; then exec sleep 60; fi\n" +
                "printf '%s\\n' \"\u0024XRAY_MPH_CACHE\" >> envs\n" +
                "exec '${core.path}' \"\u0024@\"\n"
        ) { service, dir ->
            configureSplitDns(service)
            service.onStartCommand(Intent(TProxyService.ACTION_CONNECT), 0, 1)
            await(10000) { broadcasts().any { it.action == TProxyService.ACTION_START } }
            assertEquals(listOf("run -c stdin:"), File(dir, "calls").readLines())
            assertEquals(listOf(""), File(service.filesDir, "envs").readLines())
            assertFalse(File(service.cacheDir, "matcher-cache").exists())
            assertTrue(errors().isEmpty())
        }
    }

    private fun configureSplitDns(service: TProxyService) {
        Preferences(service).routingSettingsJson =
            RoutingSettings(
                    rules =
                        listOf(
                            RoutingRule(
                                name = "Direct sites",
                                kind = RuleKind.GEOSITE,
                                values = listOf("test"),
                                target = RouteTarget.DIRECT,
                            )
                        )
                )
                .encode()
        File(service.filesDir, "geosite.dat")
            .writeBytes(
                byteArrayOf(10, 21, 10, 4, 84, 69, 83, 84, 18, 13, 8, 3, 18, 9) +
                    "localhost".toByteArray()
            )
    }
}
