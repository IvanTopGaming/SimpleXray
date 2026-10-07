package com.simplexray.an.feature.routing

import android.app.Application
import com.google.gson.JsonParser
import com.simplexray.an.core.config.AppConfig
import com.simplexray.an.core.config.routing.RoutingServerTags
import com.simplexray.an.core.runtime.process.CoreRuntimeProcess
import com.simplexray.an.feature.dns.model.DnsSettings
import com.simplexray.an.feature.kernel.model.KernelSettings
import com.simplexray.an.feature.kernel.model.ServerDomainStrategy
import com.simplexray.an.feature.routing.model.*
import com.simplexray.an.feature.routing.server.RoutingServerSources
import com.simplexray.an.prefs.Preferences
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoutingServerIntegrationTest {
    private val custom = RoutingBlock(RouteTarget.PROXY, "suffix:gemini.test", "route_gemini")

    private fun settings(vararg first: RoutingBlock): RoutingSettings {
        val blocks = first.toList() + RoutingBlocks.defaultOrder.map { RoutingBlock(it) }
        return RoutingSettings(
            bypassLan = false,
            blocks = blocks,
            rules = blocks.flatMap(RoutingBlocks::parse),
        )
    }

    private fun server(port: Int, address: String = "127.0.0.1") =
        """{"outbounds":[{"tag":"proxy","protocol":"http","settings":{"address":"$address","port":$port}}]}"""

    @Test
    fun presetsStripLocalReferencesAndRequireReselectionOnImport() {
        val ref =
            RoutingServerRef("private-server.json", "Private", "subscription-id", "a".repeat(64))
        val routing = settings(custom.copy(server = ref))
        val preset =
            RoutingPreset(
                routing,
                "https://example.test/geoip.dat",
                "https://example.test/geosite.dat",
            )
        val encoded = preset.encode()
        assertFalse(encoded.contains("private-server"))
        assertFalse(encoded.contains("subscription-id"))
        assertFalse(encoded.contains("fingerprint"))
        assertEquals(2, JsonParser.parseString(encoded).asJsonObject["version"].asInt)
        val imported = requireNotNull(RoutingPreset.detect(preset.encodeLink())).routing
        assertEquals(routing.rules, imported.rules)
        assertEquals(routing.blocks!!.map { it.id }, imported.blocks!!.map { it.id })
        assertNull(imported.blocks!!.first().server)
        val suppliedReference =
            JsonParser.parseString(encoded).asJsonObject.apply {
                add("routing", JsonParser.parseString(routing.encode()))
            }
        assertNull(
            RoutingPreset.decode(suppliedReference.toString()).routing.blocks!!.first().server
        )
        val directory = Files.createTempDirectory("unbound-routes").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                RoutingServerSources.read(imported, directory, emptyList())
            }
            val empty = settings(custom.copy(text = ""))
            assertTrue(RoutingServerSources.read(empty, directory, emptyList()).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun additionalProfilesHaveIsolatedDependencyTagsAndBootstrapDomains() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        prefs.dnsSettingsJson = DnsSettings(fakeIpEnabled = false).encode()
        prefs.kernelSettingsJson =
            KernelSettings(serverDomainStrategy = ServerDomainStrategy.USE_IP, handshake = 7)
                .encode()
        val secondary =
            """{"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"address":"inner.test","port":1080},"streamSettings":{"sockopt":{"dialerProxy":"hop"}}},{"tag":"hop","protocol":"http","settings":{"address":"hop.test","port":8080}}]}"""
        val other = custom.copy(id = "route_other", text = "suffix:other.test")
        val root =
            JsonParser.parseString(
                    AppConfig(prefs)
                        .buildProfile(
                            server(8080, "main.test"),
                            settings(custom, other),
                            serverSources = mapOf(custom.id to secondary, other.id to secondary),
                        )
                )
                .asJsonObject
        val outbounds = root.getAsJsonArray("outbounds").map { it.asJsonObject }
        val tags = outbounds.map { it["tag"].asString }
        assertEquals(tags.size, tags.distinct().size)
        for (block in listOf(custom, other)) {
            val tag = RoutingServerTags.outbound(block.id)
            val outbound = outbounds.single { it["tag"].asString == tag }
            assertEquals(
                "${tag}_chain_1",
                outbound
                    .getAsJsonObject("streamSettings")
                    .getAsJsonObject("sockopt")["dialerProxy"]
                    .asString,
            )
            assertFalse(outbound.getAsJsonObject("mux")["enabled"].asBoolean)
        }
        val bootstrapDomains =
            root
                .getAsJsonObject("dns")
                .getAsJsonArray("servers")
                .filter { it.isJsonObject && it.asJsonObject["tag"]?.asString == "__sx_server_dns" }
                .flatMap {
                    it.asJsonObject.getAsJsonArray("domains").map { domain -> domain.asString }
                }
                .toSet()
        assertEquals(setOf("full:main.test", "full:inner.test", "full:hop.test"), bootstrapDomains)
        assertEquals(
            7,
            root
                .getAsJsonObject("policy")
                .getAsJsonObject("levels")
                .getAsJsonObject("0")["handshake"]
                .asInt,
        )
        assertThrows(IllegalArgumentException::class.java) {
            AppConfig(prefs).buildProfile(server(8080), settings(custom))
        }
    }

    @Test
    fun pinnedCoreRoutesTrafficSeparatelyAndDoesNotFallBackWhenCustomExitFails() {
        val core = System.getenv("SIMPLEXRAY_TEST_CORE")?.let(::File)
        assumeTrue("Supply pinned host Xray", core?.canExecute() == true)
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        prefs.dnsSettingsJson = DnsSettings(fakeIpEnabled = false).encode()
        val listen = ServerSocket(0).use { it.localPort }
        prefs.socksPort = listen
        val directory = Files.createTempDirectory("routing-server-core").toFile()
        TestProxy("MAIN").use { main ->
            TestProxy("CUSTOM").use { extra ->
                val json =
                    JsonParser.parseString(
                            AppConfig(prefs)
                                .buildProfile(
                                    server(main.port),
                                    settings(custom),
                                    serverSources = mapOf(custom.id to server(extra.port)),
                                )
                        )
                        .asJsonObject
                json.getAsJsonArray("inbounds")[0].asJsonObject.addProperty("protocol", "http")
                val process =
                    CoreRuntimeProcess.builder(requireNotNull(core), directory)
                        .redirectOutput(File(directory, "core.log"))
                        .start()
                try {
                    process.outputStream.use { it.write(json.toString().toByteArray()) }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    var ready = false
                    while (process.isAlive && !ready && System.nanoTime() < deadline) {
                        ready =
                            runCatching {
                                    Socket().use {
                                        it.connect(InetSocketAddress("127.0.0.1", listen), 100)
                                    }
                                }
                                .isSuccess
                        if (!ready) Thread.sleep(20)
                    }
                    assertTrue(File(directory, "core.log").readText(), ready)
                    assertTrue(request(listen, "gemini.test").contains("CUSTOM"))
                    assertTrue(request(listen, "ordinary.test").contains("MAIN"))
                    assertTrue(extra.requests.any { it.contains("gemini.test") })
                    assertFalse(main.requests.any { it.contains("gemini.test") })
                    extra.close()
                    assertFalse(request(listen, "gemini.test").contains("MAIN"))
                    assertFalse(main.requests.any { it.contains("gemini.test") })
                    assertTrue(request(listen, "ordinary.test").contains("MAIN"))
                } finally {
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                }
            }
        }
        directory.deleteRecursively()
    }

    private fun request(port: Int, host: String): String =
        runCatching {
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 5000
                    socket
                        .getOutputStream()
                        .write(
                            "GET http://$host/ HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
                                .toByteArray()
                        )
                    socket.getInputStream().bufferedReader().readText()
                }
            }
            .getOrDefault("")

    private class TestProxy(private val response: String) : Closeable {
        private val server = ServerSocket(0)
        val port = server.localPort
        val requests = CopyOnWriteArrayList<String>()
        private val worker =
            thread(isDaemon = true) {
                while (!server.isClosed) runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val reader = socket.getInputStream().bufferedReader()
                        val connect = reader.readLine() ?: return@use
                        requests.add(connect)
                        while (!reader.readLine().isNullOrEmpty()) {}
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                        while (!reader.readLine().isNullOrEmpty()) {}
                        output.write(
                            "HTTP/1.1 200 OK\r\nContent-Length: ${response.length}\r\nConnection: close\r\n\r\n$response"
                                .toByteArray()
                        )
                    }
                }
            }

        override fun close() {
            server.close()
            worker.join(3500)
        }
    }
}
