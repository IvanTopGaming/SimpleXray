package com.simplexray.an.feature.subscriptions.routing

import com.google.gson.JsonParser
import com.google.protobuf.CodedOutputStream
import com.simplexray.an.core.config.profile.ProfileGeodata
import com.simplexray.an.core.config.routing.RoutingCompiler
import com.simplexray.an.core.config.routing.RoutingLayer
import com.simplexray.an.core.runtime.process.CoreRuntimeProcess
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingRule
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.routing.model.RuleKind
import java.io.ByteArrayOutputStream
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

class SubscriptionRoutingCompileTest {
    private fun port() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun source(listen: Int) =
        """{"inbounds":[{"tag":"client","listen":"127.0.0.1","port":$listen,"protocol":"http"}],"outbounds":[{"tag":"direct","protocol":"freedom"}]}"""

    private fun rule(id: String, kind: RuleKind, value: String, target: RouteTarget) =
        RoutingRule(id, "Правило", kind, listOf(value), target)

    private val settings = RoutingSettings(defaultRoute = RouteTarget.DIRECT)

    @Test
    fun subscriptionRulesFollowGlobalRulesAndUseOwnDatabases() {
        val global =
            settings.copy(rules = listOf(rule("g", RuleKind.FULL_DOMAIN, "keep.example", RouteTarget.DIRECT)))
        val layer =
            RoutingLayer(
                listOf(
                    rule("s1", RuleKind.GEOSITE, "test", RouteTarget.BLOCK),
                    rule("s2", RuleKind.GEOIP, "!ru", RouteTarget.BLOCK),
                ),
                "sub-a-geoip.dat",
                "sub-a-geosite.dat",
            )
        val compiled = RoutingCompiler.compile(source(port()), global, listOf(layer))
        val rules =
            JsonParser.parseString(compiled).asJsonObject.getAsJsonObject("routing").getAsJsonArray("rules")
        val values = rules.map { it.asJsonObject.get("domain") ?: it.asJsonObject.get("ip") }.map { it?.toString() }
        assertEquals(
            listOf("[\"full:keep.example\"]", "[\"ext:sub-a-geosite.dat:test\"]", "[\"ext:sub-a-geoip.dat:!ru\"]", null),
            values.drop(values.indexOfFirst { it != null && !it.contains("10.0.0.0/8") }),
        )
        assertEquals(
            setOf("sub-a-geoip.dat", "sub-a-geosite.dat"),
            ProfileGeodata.requiredFiles(compiled),
        )
    }

    @Test
    fun foreignExternalFilesStayForbidden() {
        listOf("ext:other.dat:test", "ext:geosite.dat:test", "ext-domain:sub-a-geosite.dat:test", "EXT:sub-a-geosite.dat:test")
            .forEach { value ->
                val config = """{"routing":{"rules":[{"domain":["$value"],"outboundTag":"x"}]}}"""
                assertThrows(value, IllegalArgumentException::class.java) {
                    ProfileGeodata.requiredFiles(config)
                }
            }
        assertThrows(IllegalArgumentException::class.java) {
            RoutingLayer(emptyList(), "sub-a-geosite.dat", "sub-a-geoip.dat").validate()
        }
    }

    @Test
    fun overlappingServerBlocksAreRejected() {
        val serverRule = rule("s", RuleKind.DOMAIN, "a.example", RouteTarget.PROXY).copy(serverBlockId = "sub_0")
        assertThrows(IllegalArgumentException::class.java) {
            RoutingLayer.validate(listOf(serverRule.copy(id = "g")), listOf(RoutingLayer(listOf(serverRule))))
        }
    }

    @Test
    fun realCoreAppliesSubscriptionDatabaseAndGlobalOverride() {
        val core = System.getenv("SIMPLEXRAY_TEST_CORE")?.let(::File)
        assumeTrue("Host Xray must be explicitly supplied", core?.canExecute() == true)
        val dir = Files.createTempDirectory("subscription-routing-core").toFile()
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
                            .write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray())
                    }
                }
            }
        try {
            database(File(dir, "geosite.dat"), "other.invalid")
            database(File(dir, "sub-a-geosite.dat"), "localhost")
            val layer =
                RoutingLayer(
                    listOf(rule("s", RuleKind.GEOSITE, "test", RouteTarget.BLOCK)),
                    geositeFile = "sub-a-geosite.dat",
                )
            val listen = port()
            val blocked = RoutingCompiler.compile(source(listen), settings, listOf(layer))
            assertFalse(request(core!!, dir, blocked, listen, http.localPort))
            val override =
                settings.copy(rules = listOf(rule("g", RuleKind.FULL_DOMAIN, "localhost", RouteTarget.DIRECT)))
            val allowed = RoutingCompiler.compile(source(listen), override, listOf(layer))
            assertTrue(request(core, dir, allowed, listen, http.localPort))
        } finally {
            http.close()
            responder.join(2000)
            dir.deleteRecursively()
        }
    }

    private fun request(core: File, assets: File, config: String, listen: Int, target: Int): Boolean {
        val log = File(assets, "core.log")
        val child = CoreRuntimeProcess.builder(core, assets).redirectOutput(log).start()
        try {
            child.outputStream.use { it.write(config.toByteArray()) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var ready = false
            while (child.isAlive && !ready && System.nanoTime() < deadline) {
                ready =
                    runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", listen), 100) } }
                        .isSuccess
                if (!ready) Thread.sleep(20)
            }
            assertTrue("Core must open its listener: ${log.readText()}", ready)
            return runCatching {
                    Socket("127.0.0.1", listen).use { socket ->
                        socket.soTimeout = 2000
                        socket
                            .getOutputStream()
                            .write(
                                "GET http://localhost:$target/ HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                                    .toByteArray()
                            )
                        socket.getInputStream().bufferedReader().readLine().orEmpty().contains("200 OK")
                    }
                }
                .getOrDefault(false)
        } finally {
            child.destroy()
            child.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun encoded(block: (CodedOutputStream) -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        CodedOutputStream.newInstance(buffer).also {
            block(it)
            it.flush()
        }
        return buffer.toByteArray()
    }

    private fun database(file: File, domain: String) {
        file.writeBytes(
            encoded { list ->
                list.writeByteArray(
                    1,
                    encoded { site ->
                        site.writeString(1, "TEST")
                        site.writeByteArray(
                            2,
                            encoded { entry ->
                                entry.writeEnum(1, 3)
                                entry.writeString(2, domain)
                            },
                        )
                    },
                )
            }
        )
    }
}
