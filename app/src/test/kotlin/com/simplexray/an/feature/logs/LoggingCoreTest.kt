package com.simplexray.an.feature.logs

import android.app.Application
import com.simplexray.an.core.config.ConfigUtils
import com.simplexray.an.core.config.logging.LoggingConfigCompiler
import com.simplexray.an.core.runtime.stats.CoreStatsClient
import com.simplexray.an.feature.logs.model.LogSettings
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LoggingCoreTest {
    @Test
    fun pinnedCoreAcceptsEveryLogLevelAndMask() {
        val core = System.getenv("SIMPLEXRAY_TEST_CORE")?.let(::File)
        assumeTrue("Host Xray must be explicitly supplied", core?.canExecute() == true)
        val dir = Files.createTempDirectory("logging-core").toFile()
        try {
            listOf("debug", "info", "warning", "error", "none").forEach { level ->
                val config =
                    File(dir, "config.json").apply {
                        writeText(
                            LoggingConfigCompiler.compile(
                                """{"outbounds":[{"protocol":"freedom"}]}""",
                                LogSettings(level, true, true, true),
                            )
                        )
                    }
                val log = File(dir, "core.log")
                val process =
                    ProcessBuilder(requireNotNull(core).path, "run", "-test", "-c", config.path)
                        .redirectErrorStream(true)
                        .redirectOutput(log)
                        .start()
                try {
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                    assertEquals(log.readText(), 0, process.exitValue())
                } finally {
                    process.destroyForcibly()
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun disabledTrafficLeavesSystemRpcReadyAndNoTrafficCounters() = runBlocking {
        val core = System.getenv("SIMPLEXRAY_TEST_CORE")?.let(::File)
        assumeTrue("Host Xray must be explicitly supplied", core?.canExecute() == true)
        val dir = Files.createTempDirectory("logging-stats").toFile()
        val reservations = List(2) { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) }
        val (clientPort, apiPort) = reservations.map { it.localPort }
        reservations.forEach { it.close() }
        var child: Process? = null
        try {
            val config =
                File(dir, "config.json").apply {
                    writeText(
                        ConfigUtils.injectStatsService(
                            "127.0.0.1",
                            apiPort,
                            LoggingConfigCompiler.compile(
                                """{"inbounds":[{"tag":"__sx_client","listen":"127.0.0.1","port":$clientPort,"protocol":"socks"}],"outbounds":[{"protocol":"freedom"}]}""",
                                LogSettings(trafficStats = false),
                            ),
                            false,
                        )
                    )
                }
            val log = File(dir, "core.log")
            child =
                ProcessBuilder(requireNotNull(core).path, "run", "-c", config.path)
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .start()
            CoreStatsClient.create("127.0.0.1", apiPort).use { stats ->
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                var ready = false
                while (child.isAlive && System.nanoTime() < deadline) {
                    if (stats.getSystemStats() != null) {
                        ready = true
                        break
                    }
                    delay(20)
                }
                assertTrue(log.readText(), ready)
                assertNull(stats.getTraffic())
            }
        } finally {
            child?.let {
                it.destroy()
                if (!it.waitFor(1, TimeUnit.SECONDS)) {
                    it.destroyForcibly()
                    it.waitFor(1, TimeUnit.SECONDS)
                }
            }
            dir.deleteRecursively()
        }
    }
}
