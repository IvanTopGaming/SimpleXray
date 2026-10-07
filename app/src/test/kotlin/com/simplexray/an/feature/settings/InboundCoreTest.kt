package com.simplexray.an.feature.settings

import com.google.gson.JsonParser
import com.simplexray.an.core.config.inbound.InboundConfigCompiler
import com.simplexray.an.core.config.ownership.OwnedConfig
import com.simplexray.an.feature.settings.model.InboundSettings
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class InboundCoreTest {
    private val executable = System.getenv("SIMPLEXRAY_TEST_CORE")?.let(::File)

    @Test
    fun separateHttpListenerConnectsToRealLocalTarget() {
        assumeTrue(executable?.canExecute() == true)
        val directory = Files.createTempDirectory("inbound-core").toFile()
        val socks = freePort()
        var http = freePort()
        while (http == socks) http = freePort()
        val settings = InboundSettings(socksPort = socks, httpPort = http)
        val source =
            """{"protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":1}]}}"""
        val configured =
            JsonParser.parseString(
                    InboundConfigCompiler.configure(
                        OwnedConfig.build(source, "127.0.0.1", socks),
                        settings,
                    )
                )
                .asJsonObject
        configured.getAsJsonArray("outbounds")[0].asJsonObject.apply {
            addProperty("protocol", "freedom")
            remove("settings")
        }
        val config = File(directory, "config.json").apply { writeText(configured.toString()) }
        val log = File(directory, "core.log")
        val process =
            ProcessBuilder(executable!!.path, "run", "-c", config.path)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
        try {
            awaitPort(process, http, log)
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { target ->
                target.soTimeout = 3000
                val worker =
                    thread(isDaemon = true) {
                        target.accept().use { socket ->
                            socket.soTimeout = 3000
                            assertEquals(42, socket.getInputStream().read())
                            socket.getOutputStream().write(43)
                        }
                    }
                Socket("127.0.0.1", http).use { client ->
                    client.soTimeout = 3000
                    client
                        .getOutputStream()
                        .write(
                            "CONNECT 127.0.0.1:${target.localPort} HTTP/1.1\r\nHost: 127.0.0.1:${target.localPort}\r\n\r\n"
                                .toByteArray()
                        )
                    val input = client.getInputStream()
                    val response = StringBuilder()
                    while (!response.endsWith("\r\n\r\n")) {
                        val value = input.read()
                        check(value >= 0) { response.toString() }
                        response.append(value.toChar())
                    }
                    assertTrue(response.toString(), response.startsWith("HTTP/1.1 200"))
                    client.getOutputStream().write(42)
                    assertEquals(43, input.read())
                }
                worker.join(4000)
                assertFalse(worker.isAlive)
            }
        } finally {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
            directory.deleteRecursively()
        }
    }

    private fun freePort() =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun awaitPort(process: Process, port: Int, log: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (process.isAlive && System.nanoTime() < deadline) {
            if (
                runCatching {
                        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 50) }
                    }
                    .isSuccess
            )
                return
            Thread.sleep(20)
        }
        error("Core did not start: ${log.readText()}")
    }
}
