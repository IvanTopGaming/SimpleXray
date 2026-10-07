package com.simplexray.an.feature.kernel

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.core.config.dns.DnsConfigCompiler
import com.simplexray.an.core.config.kernel.KernelConfigCompiler
import com.simplexray.an.core.config.ownership.OwnedConfig
import com.simplexray.an.core.config.routing.RoutingCompiler
import com.simplexray.an.feature.dns.model.DnsSettings
import com.simplexray.an.feature.kernel.model.KernelSettings
import com.simplexray.an.feature.kernel.model.ServerDomainStrategy
import com.simplexray.an.feature.kernel.model.TcpCongestion
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingRule
import com.simplexray.an.feature.routing.model.RoutingSettings
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class KernelCoreTest {
    private val executable = System.getenv("SIMPLEXRAY_TEST_CORE")?.let(::File)
    private val uuid = "11111111-1111-1111-1111-111111111111"

    @Test
    fun routeOnlyUsesOriginalIpWhileDestinationModeUsesSniffedHost() {
        assumeTrue(executable?.canExecute() == true)
        Fixture().use { fixture ->
            for (routeOnly in listOf(true, false)) {
                val port = freePort()
                fixture.client(
                    port,
                    socksSource(freePort()),
                    KernelSettings(sniffingRouteOnly = routeOnly),
                    DnsSettings(
                        fakeIpEnabled = false,
                        primaryDns = fixture.resolver,
                        route = RouteTarget.DIRECT,
                    ),
                    RoutingSettings(
                        defaultRoute = RouteTarget.BLOCK,
                        bypassLan = false,
                        rules =
                            listOf(
                                RoutingRule(
                                    name = "Target",
                                    values = listOf("target.test"),
                                    target = RouteTarget.DIRECT,
                                )
                            ),
                    ),
                )
                assertEquals(
                    if (routeOnly) "ORIGINAL" else "SNIFFED",
                    request(port, "127.0.0.1", fixture.original.localPort),
                )
            }
        }
    }

    @Test
    fun fakeIpRestorationStillWorksWhenOrdinarySniffingIsDisabled() {
        assumeTrue(executable?.canExecute() == true)
        Fixture().use { fixture ->
            for (routeOnly in listOf(true, false)) {
                val port = freePort()
                fixture.client(
                    port,
                    socksSource(freePort()),
                    KernelSettings(
                        sniffingEnabled = false,
                        sniffingRouteOnly = routeOnly,
                        sniffingProtocols = emptySet(),
                    ),
                    DnsSettings(primaryDns = fixture.resolver, route = RouteTarget.DIRECT),
                    RoutingSettings(
                        defaultRoute = RouteTarget.BLOCK,
                        bypassLan = false,
                        rules =
                            listOf(
                                RoutingRule(
                                    name = "Fake target",
                                    values = listOf("target.test"),
                                    target = RouteTarget.DIRECT,
                                )
                            ),
                    ),
                )
                val fake = query(port, "target.test")
                assertTrue(fake.startsWith("198.19."))
                assertEquals(
                    "SNIFFED",
                    request(port, fake, fixture.original.localPort, "unrelated.invalid"),
                )
                assertThrows(Exception::class.java) {
                    request(port, "198.19.255.254", fixture.original.localPort)
                }
            }
        }
    }

    @Test
    fun compatibleXrayVmessMuxCarriesMultipleRealHttpRequests() {
        assumeTrue(executable?.canExecute() == true)
        Fixture().use { fixture ->
            val upstream = freePort()
            fixture.start(
                "vmess",
                """{"inbounds":[{"listen":"127.0.0.1","port":$upstream,"protocol":"vmess","settings":{"clients":[{"id":"$uuid"}]}}],"outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","ip":["127.0.0.0/8"]}]}}]}""",
                upstream,
            )
            val port = freePort()
            val source =
                """{"protocol":"vmess","settings":{"vnext":[{"address":"127.0.0.1","port":$upstream,"users":[{"id":"$uuid","security":"auto"}]}]}}"""
            fixture.client(
                port,
                source,
                KernelSettings(
                    muxEnabled = true,
                    muxConcurrency = 4,
                    tcpKeepAliveInterval = 30,
                    tcpUserTimeout = 1000,
                    tcpCongestion = TcpCongestion.CUBIC,
                    handshake = 8,
                    connectionIdle = 30,
                    uplinkOnly = 1,
                    downlinkOnly = 1,
                    bufferSize = 16,
                ),
                DnsSettings(
                    fakeIpEnabled = false,
                    primaryDns = fixture.resolver,
                    route = RouteTarget.DIRECT,
                ),
                RoutingSettings(defaultRoute = RouteTarget.PROXY, bypassLan = false),
            )
            repeat(3) {
                assertEquals("ORIGINAL", request(port, "127.0.0.1", fixture.original.localPort))
            }
            assertTrue(fixture.log("client-$port").contains("common/mux: dispatching request"))
        }
    }

    @Test
    fun serverHostnameBootstrapConnectsBeforeProxiedDnsWithAndWithoutFakeIp() {
        assumeTrue(executable?.canExecute() == true)
        Fixture().use { fixture ->
            val upstream = freePort()
            fixture.start(
                "socks",
                """{"inbounds":[{"listen":"127.0.0.1","port":$upstream,"protocol":"socks","settings":{"udp":true}}],"dns":{"servers":[{"address":"127.0.0.1","port":${fixture.dns.localPort}}]},"outbounds":[{"protocol":"freedom","streamSettings":{"sockopt":{"domainStrategy":"ForceIPv4"}}}]}""",
                upstream,
            )
            for (fake in listOf(false, true)) {
                val port = freePort()
                fixture.client(
                    port,
                    """{"protocol":"socks","settings":{"address":"server.test","port":$upstream}}""",
                    KernelSettings(
                        sniffingEnabled = false,
                        serverDomainStrategy = ServerDomainStrategy.USE_IPV4,
                    ),
                    DnsSettings(
                        fakeIpEnabled = fake,
                        primaryDns = fixture.resolver,
                        route = RouteTarget.PROXY,
                    ),
                    RoutingSettings(defaultRoute = RouteTarget.PROXY, bypassLan = false),
                )
                val address = query(port, "target.test")
                assertEquals(fake, address.startsWith("198.19."))
                assertEquals("SNIFFED", request(port, address, fixture.original.localPort))
                if (fake) {
                    val serverFake = query(port, "server.test")
                    assertTrue(
                        "Application DNS for the proxy endpoint must still use FakeIP: $serverFake",
                        serverFake.startsWith("198.19."),
                    )
                    assertEquals(
                        "ORIGINAL",
                        request(port, serverFake, fixture.original.localPort, "unrelated.invalid"),
                    )
                }
                val access = fixture.access("client-$port")
                assertTrue(access, access.contains("__sx_server_dns -> __sx_server_bootstrap"))
                if (!fake) assertTrue(access, access.contains("__sx_dns -> __sx_dns_transport"))
            }
            assertTrue(fixture.domains.contains("server.test"))
            assertTrue(fixture.domains.contains("target.test"))
        }
    }

    @Test
    fun legacyProtocolLayerChainStillBypassesInactiveXhttpTransport() {
        assumeTrue(executable?.canExecute() == true)
        Fixture().use { fixture ->
            val upstream = freePort()
            val hop = freePort()
            for (port in listOf(upstream, hop)) {
                fixture.start(
                    "socks-$port",
                    """{"inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"socks","settings":{"udp":true}}],"outbounds":[{"protocol":"freedom"}]}""",
                    port,
                )
            }
            for (transportLayer in
                listOf("", ",\"transportLayer\":false", ",\"transportLayer\":true")) {
                val port = freePort()
                val network = if (transportLayer.endsWith("true")) "raw" else "xhttp"
                fixture.client(
                    port,
                    """{"outbounds":[{"tag":"entry","protocol":"socks","settings":{"address":"127.0.0.1","port":$upstream},"proxySettings":{"tag":"hop"$transportLayer},"streamSettings":{"network":"$network","xhttpSettings":{"path":"/must-not-be-used"}}},{"tag":"hop","protocol":"socks","settings":{"address":"127.0.0.1","port":$hop}}]}""",
                    KernelSettings(sniffingEnabled = false),
                    DnsSettings(
                        fakeIpEnabled = false,
                        primaryDns = fixture.resolver,
                        route = RouteTarget.DIRECT,
                    ),
                    RoutingSettings(defaultRoute = RouteTarget.PROXY, bypassLan = false),
                )
                assertEquals("ORIGINAL", request(port, "127.0.0.1", fixture.original.localPort))
                assertTrue(fixture.access("socks-$hop").contains("127.0.0.1:$upstream"))
            }
        }
    }

    @Test
    fun bootstrapDohKeepsPinnedAddressAndTlsHostnameWhenDnsRouteIsProxy() {
        assumeTrue(executable?.canExecute() == true)
        Fixture().use { fixture ->
            ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).use { tls ->
                tls.soTimeout = 7000
                val port = freePort()
                fixture.client(
                    port,
                    socksSource(freePort(), "server.test"),
                    KernelSettings(
                        sniffingEnabled = false,
                        serverDomainStrategy = ServerDomainStrategy.USE_IPV4,
                    ),
                    DnsSettings(
                        fakeIpEnabled = false,
                        primaryDns = "https://resolver.invalid:${tls.localPort}/dns-query",
                        primaryBootstrap = "127.0.0.1",
                        route = RouteTarget.PROXY,
                    ),
                    RoutingSettings(defaultRoute = RouteTarget.PROXY, bypassLan = false),
                )
                val requester =
                    thread(isDaemon = true) {
                        runCatching { request(port, "127.0.0.1", fixture.original.localPort) }
                    }
                tls.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = DataInputStream(socket.getInputStream())
                    assertEquals(22, input.readUnsignedByte())
                    input.readUnsignedShort()
                    val hello = ByteArray(input.readUnsignedShort()).also(input::readFully)
                    assertTrue(String(hello, Charsets.ISO_8859_1).contains("resolver.invalid"))
                }
                requester.join(6000)
                assertFalse(requester.isAlive)
                assertTrue(fixture.domains.isEmpty())
            }
        }
    }

    private fun socksSource(port: Int, host: String = "127.0.0.1") =
        """{"protocol":"socks","settings":{"servers":[{"address":"$host","port":$port}]}}"""

    private fun freePort() =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun connect(port: Int, address: String, target: Int): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
            socket.soTimeout = 5000
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            output.write(byteArrayOf(5, 1, 0))
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0)
            output.write(byteArrayOf(5, 1, 0, 1))
            output.write(InetAddress.getByName(address).address)
            output.writeShort(target)
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0)
            input.readUnsignedByte()
            val length =
                when (input.readUnsignedByte()) {
                    1 -> 4
                    4 -> 16
                    else -> input.readUnsignedByte()
                }
            input.readFully(ByteArray(length))
            input.readUnsignedShort()
            return socket
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    private fun request(
        port: Int,
        address: String,
        target: Int,
        host: String = "target.test",
    ): String =
        connect(port, address, target).use { socket ->
            socket
                .getOutputStream()
                .write("GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            val response = socket.getInputStream().bufferedReader().readText()
            check(response.startsWith("HTTP/1.1 200 OK")) { response }
            response.substringAfter("\r\n\r\n")
        }

    private fun query(port: Int, domain: String): String =
        connect(port, "9.9.9.9", 53).use { socket ->
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeShort(42)
                out.writeShort(0x100)
                out.writeShort(1)
                repeat(3) { out.writeShort(0) }
                domain.split('.').forEach {
                    out.writeByte(it.length)
                    out.writeBytes(it)
                }
                out.writeByte(0)
                out.writeShort(1)
                out.writeShort(1)
            }
            DataOutputStream(socket.getOutputStream()).apply {
                writeShort(bytes.size())
                write(bytes.toByteArray())
            }
            val input = DataInputStream(socket.getInputStream())
            val response = ByteArray(input.readUnsignedShort()).also(input::readFully)
            check((response[3].toInt() and 15) == 0)
            check(response[7].toInt() > 0)
            InetAddress.getByAddress(response.takeLast(4).toByteArray()).hostAddress!!
        }

    private inner class Fixture : AutoCloseable {
        val dir = Files.createTempDirectory("kernel-core").toFile()
        val dns = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val resolver = "127.0.0.1:${dns.localPort}"
        val domains = CopyOnWriteArrayList<String>()
        val original = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        private val sniffed =
            ServerSocket(original.localPort, 20, InetAddress.getByName("127.0.0.2"))
        private val children = mutableListOf<Process>()
        private val workers = mutableListOf<Thread>()

        init {
            workers +=
                thread(isDaemon = true) {
                    while (!dns.isClosed) runCatching {
                        val packet = DatagramPacket(ByteArray(4096), 4096)
                        dns.receive(packet)
                        val query = packet.data.copyOf(packet.length)
                        var end = 12
                        val labels = mutableListOf<String>()
                        while (query[end].toInt() != 0) {
                            val length = query[end].toInt() and 255
                            labels.add(String(query, end + 1, length))
                            end += length + 1
                        }
                        end++
                        val domain = labels.joinToString(".")
                        domains.add(domain)
                        val type =
                            ((query[end].toInt() and 255) shl 8) or (query[end + 1].toInt() and 255)
                        val ip =
                            InetAddress.getByName(
                                    if (type == 28) "::1"
                                    else if (domain == "target.test") "127.0.0.2" else "127.0.0.1"
                                )
                                .address
                        val bytes = ByteArrayOutputStream()
                        DataOutputStream(bytes).use { out ->
                            out.write(query, 0, 2)
                            out.writeShort(0x8180)
                            out.writeShort(1)
                            out.writeShort(1)
                            out.writeInt(0)
                            out.write(query, 12, end + 4 - 12)
                            out.writeShort(0xc00c)
                            out.writeShort(type)
                            out.writeShort(1)
                            out.writeInt(60)
                            out.writeShort(ip.size)
                            out.write(ip)
                        }
                        val response = bytes.toByteArray()
                        dns.send(DatagramPacket(response, response.size, packet.socketAddress))
                    }
                }
            for ((server, body) in listOf(original to "ORIGINAL", sniffed to "SNIFFED")) workers +=
                thread(isDaemon = true) {
                    while (!server.isClosed) runCatching {
                        server.accept().use { socket ->
                            socket.soTimeout = 2000
                            val input = socket.getInputStream().bufferedReader()
                            while (!input.readLine().isNullOrEmpty()) {}
                            socket
                                .getOutputStream()
                                .write(
                                    "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                                        .toByteArray()
                                )
                        }
                    }
                }
        }

        fun client(
            port: Int,
            source: String,
            kernel: KernelSettings,
            dns: DnsSettings,
            routing: RoutingSettings,
        ): Process =
            start(
                "client-$port",
                RoutingCompiler.compile(
                    KernelConfigCompiler.compile(
                        DnsConfigCompiler.compile(
                            OwnedConfig.build(source, "127.0.0.1", port),
                            dns,
                            false,
                        ),
                        kernel,
                    ),
                    routing,
                ),
                port,
            )

        fun log(name: String) = File(dir, "$name.log").readText()

        fun access(name: String) = File(dir, "$name.access").readText()

        fun start(name: String, config: String, port: Int): Process {
            val configured =
                JsonParser.parseString(config).asJsonObject.apply {
                    add(
                        "log",
                        JsonObject().apply {
                            addProperty("loglevel", "debug")
                            addProperty("access", File(dir, "$name.access").path)
                        },
                    )
                }
            val file = File(dir, "$name.json").apply { writeText(configured.toString()) }
            val process =
                ProcessBuilder(executable!!.path, "run", "-c", file.path)
                    .redirectErrorStream(true)
                    .redirectOutput(File(dir, "$name.log"))
                    .apply { environment()["XRAY_LOCATION_ASSET"] = dir.path }
                    .start()
            children.add(process)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (process.isAlive && System.nanoTime() < deadline) {
                if (
                    runCatching {
                            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 50) }
                        }
                        .isSuccess
                )
                    return process
                Thread.sleep(20)
            }
            error("Core failed to start: ${log(name)}")
        }

        override fun close() {
            children.forEach { process ->
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(1, TimeUnit.SECONDS)
                }
            }
            dns.close()
            original.close()
            sniffed.close()
            workers.forEach { it.join(2000) }
            dir.deleteRecursively()
        }
    }
}
