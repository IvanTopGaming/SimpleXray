package com.simplexray.an.feature.routing

import com.simplexray.an.core.config.ownership.OwnedConfig
import com.simplexray.an.core.config.routing.RoutingCompiler
import com.simplexray.an.core.runtime.validation.CoreConfigValidator
import com.simplexray.an.feature.routing.model.DomainStrategy
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingRule
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.routing.state.RoutingEditor
import java.io.File
import java.net.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RoutingCoreTest {
    private val executable = System.getenv("SIMPLEXRAY_TEST_CORE")?.let(::File)

    private fun port() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    @Test
    fun pinnedCoreAcceptsGeneratedRoutesAndRejectsInvalidConfig() = runBlocking {
        assumeTrue("Host Xray must be explicitly supplied", executable?.canExecute() == true)
        val dir = Files.createTempDirectory("routing-core").toFile()
        try {
            val validator = CoreConfigValidator(executable!!, dir, dir)
            val source = source(port(), port())
            for (target in RouteTarget.entries) for (strategy in DomainStrategy.entries) {
                val settings =
                    RoutingSettings(
                        enabled = true,
                        defaultRoute = target,
                        domainStrategy = strategy,
                        rules =
                            listOf(
                                RoutingRule(
                                    name = "Domain",
                                    values = listOf("example.com"),
                                    target = RouteTarget.BLOCK,
                                )
                            ),
                    )
                validator.validate(RoutingCompiler.compile(source, settings))
                validator.validate(
                    RoutingCompiler.compile(
                        OwnedConfig.build(
                            source,
                            "127.0.0.1",
                            port(),
                            dnsServers = listOf("1.1.1.1"),
                        ),
                        settings,
                    )
                )
            }
            try {
                validator.validate("""{"outbounds":[{"protocol":"not-a-protocol"}]}""")
                fail()
            } catch (_: IllegalArgumentException) {}
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun actualTrafficHonorsBlockDirectProxyAndRuleOrder() {
        assumeTrue("Host Xray must be explicitly supplied", executable?.canExecute() == true)
        val dir = Files.createTempDirectory("routing-traffic").toFile()
        val http = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val responder =
            thread(isDaemon = true) {
                while (!http.isClosed) runCatching {
                    http.accept().use { socket ->
                        socket.soTimeout = 2000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) {}
                        socket
                            .getOutputStream()
                            .write(
                                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                                    .toByteArray()
                            )
                    }
                }
            }
        val upstreamPort = port()
        val upstream =
            start(
                dir,
                "upstream",
                """{"inbounds":[{"listen":"127.0.0.1","port":$upstreamPort,"protocol":"vless","settings":{"clients":[{"id":"00000000-0000-0000-0000-000000000001"}],"decryption":"none"}}],"outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","ip":["127.0.0.0/8","::1/128"]}]}}]}""",
                upstreamPort,
            )
        try {
            val block =
                RoutingRule(
                    name = "Block",
                    values = listOf("localhost"),
                    target = RouteTarget.BLOCK,
                )
            val direct = block.copy(id = "direct", target = RouteTarget.DIRECT)
            fun request(settings: RoutingSettings): Int {
                val clientPort = port()
                val owned =
                    OwnedConfig.build(
                        source(clientPort, upstreamPort),
                        "127.0.0.1",
                        clientPort,
                        dnsServers = listOf("1.1.1.1"),
                    )
                val client =
                    start(dir, "client", RoutingCompiler.compile(owned, settings), clientPort)
                try {
                    val connection =
                        URL("http://localhost:${http.localPort}/")
                            .openConnection(
                                Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", clientPort))
                            ) as HttpURLConnection
                    connection.connectTimeout = 1200
                    connection.readTimeout = 1200
                    return try {
                        connection.responseCode
                    } catch (_: Exception) {
                        -1
                    } finally {
                        connection.disconnect()
                    }
                } finally {
                    stop(client)
                }
            }
            val custom = RoutingSettings(enabled = true, bypassLan = false)
            assertEquals(200, request(custom))
            assertNotEquals(200, request(custom.copy(rules = listOf(block, direct))))
            assertEquals(
                200,
                request(
                    custom.copy(defaultRoute = RouteTarget.BLOCK, rules = listOf(direct, block))
                ),
            )
            assertNotEquals(
                200,
                request(
                    custom.copy(
                        defaultRoute = RouteTarget.BLOCK,
                        rules = listOf(direct.copy(enabled = false)),
                    )
                ),
            )
            var saved = custom.encode()
            val editor = RoutingEditor({ saved }, { saved = it })
            assertTrue(editor.saveBlock(RouteTarget.DIRECT, "suffix:localhost"))
            assertTrue(editor.saveBlock(RouteTarget.PROXY, "full:localhost"))
            assertTrue(editor.saveBlock(RouteTarget.BLOCK, "suffix:localhost"))
            assertTrue(editor.moveBlock(RouteTarget.BLOCK, 0))
            assertNotEquals(200, request(editor.state.value.draft))
            assertTrue(editor.moveBlock(RouteTarget.DIRECT, 0))
            assertEquals(200, request(editor.state.value.draft))
            assertTrue(editor.moveBlock(RouteTarget.PROXY, 0))
            assertEquals(200, request(editor.state.value.draft))
            stop(upstream)
            assertNotEquals(200, request(editor.state.value.draft))
            assertTrue(editor.moveBlock(RouteTarget.DIRECT, 0))
            assertEquals(200, request(editor.state.value.draft))
            assertNotEquals(200, request(custom))
            assertEquals(200, request(custom.copy(defaultRoute = RouteTarget.DIRECT)))
        } finally {
            stop(upstream)
            http.close()
            responder.join(2500)
            dir.deleteRecursively()
        }
    }

    private fun source(client: Int, upstream: Int) =
        """{"inbounds":[{"listen":"127.0.0.1","port":$client,"protocol":"http","tag":"client"}],"outbounds":[{"protocol":"freedom","tag":"direct"},{"protocol":"vless","tag":"proxy","settings":{"vnext":[{"address":"127.0.0.1","port":$upstream,"users":[{"id":"00000000-0000-0000-0000-000000000001","encryption":"none"}]}]}}],"routing":{"rules":[{"network":"tcp,udp","outboundTag":"direct"}]}}"""

    private fun start(dir: File, name: String, config: String, port: Int): Process {
        val input = File(dir, "$name.json").apply { writeText(config) }
        val child =
            ProcessBuilder(executable!!.path, "run", "-c", input.path)
                .redirectErrorStream(true)
                .redirectOutput(File("/dev/null"))
                .start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (child.isAlive && System.nanoTime() < deadline) {
            if (
                runCatching {
                        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 50) }
                    }
                    .isSuccess
            )
                return child
            Thread.sleep(20)
        }
        stop(child)
        error("Host core did not open its listener")
    }

    private fun stop(child: Process) {
        child.destroy()
        if (!child.waitFor(1, TimeUnit.SECONDS)) {
            child.destroyForcibly()
            child.waitFor(1, TimeUnit.SECONDS)
        }
    }
}
