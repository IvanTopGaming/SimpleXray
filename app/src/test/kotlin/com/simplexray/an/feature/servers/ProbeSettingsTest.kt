package com.simplexray.an.feature.servers

import com.simplexray.an.core.network.probe.buildProbeHttpRequest
import com.simplexray.an.core.network.probe.probeTcp
import com.simplexray.an.core.network.probe.probeTcpEndpoint
import com.simplexray.an.feature.servers.model.ProbeMethod
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ProbeSettingsTest {
    @Test
    fun tcpSelectsTaggedProxyAndDoesNotProbeDirectOrOtherServers() {
        val config =
            """{"outbounds":[{"protocol":"freedom","tag":"direct"},{"protocol":"trojan","settings":{"servers":[{"address":"wrong.test","port":443}]}},{"protocol":"vless","tag":"proxy","settings":{"vnext":[{"address":"right.test","port":8443}]}}]}"""
        assertEquals("right.test", probeTcpEndpoint(config).host)
        assertEquals(8443, probeTcpEndpoint(config).port)
    }

    @Test
    fun tcpFindsOnlyProxyWithoutTagAndSupportsServerBasedProtocols() {
        for (protocol in listOf("trojan", "shadowsocks", "socks", "http")) {
            val config =
                """{"outbounds":[{"protocol":"freedom"},{"protocol":"$protocol","settings":{"servers":[{"address":"127.0.0.1","port":1080}]}},{"protocol":"blackhole"}]}"""
            assertEquals("127.0.0.1", probeTcpEndpoint(config).host)
            assertEquals(1080, probeTcpEndpoint(config).port)
        }
    }

    @Test
    fun tcpRejectsAmbiguousUnsupportedAndMalformedEndpoints() {
        val configs =
            listOf(
                """{"outbounds":[{"protocol":"vless"},{"protocol":"trojan"}]}""",
                """{"outbounds":[{"tag":"proxy","protocol":"freedom"}]}""",
                """{"outbounds":[{"tag":"proxy","protocol":"wireguard"}]}""",
                """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"","port":443}]}}]}""",
                """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"test","port":65536}]}}]}""",
                """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"test","port":443.5}]}}]}""",
                """{"outbounds":[{"tag":"proxy","protocol":"trojan","settings":{"servers":[{"address":"a.test","port":443},{"address":"b.test","port":443}]}}]}""",
            )
        for (config in configs) {
            try {
                probeTcpEndpoint(config)
                fail("Endpoint must be rejected: $config")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun tcpMeasuresPortReachabilityWithoutHttpTraffic() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val config =
                """{"outbounds":[{"tag":"proxy","protocol":"vmess","settings":{"vnext":[{"address":"127.0.0.1","port":${server.localPort}}]}}]}"""
            assertTrue(probeTcp(config, 1000) >= 1)
            server.accept().use { socket ->
                socket.soTimeout = 1000
                assertEquals(-1, socket.getInputStream().read())
            }
        }
    }

    @Test
    fun tcpRejectsClosedPort() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val config =
            """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":$port}]}}]}"""
        try {
            probeTcp(config, 200)
            fail("Closed port must fail")
        } catch (_: java.io.IOException) {}
    }

    @Test
    fun tcpRejectsInvalidTimeoutBeforeConnecting() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val config =
                """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":${server.localPort}}]}}]}"""
            for (timeout in listOf(0, -1, 30001)) {
                try {
                    probeTcp(config, timeout)
                    fail("Invalid timeout must fail")
                } catch (_: IllegalArgumentException) {}
            }
        }
    }

    @Test
    fun httpMethodsPreserveTargetAndStripCredentialsAndFragment() {
        val target = "http://user:secret@example.test:8080/check?q=kept#ignored".toHttpUrl()
        for ((method, verb) in
            listOf(ProbeMethod.HTTP_GET to "GET", ProbeMethod.HTTP_HEAD to "HEAD")) {
            val request = buildProbeHttpRequest(target, "Basic test", method)
            assertTrue(
                request.startsWith("$verb http://example.test:8080/check?q=kept HTTP/1.1\r\n")
            )
            assertTrue(request.contains("Host: example.test:8080\r\n"))
            assertTrue(request.contains("Proxy-Authorization: Basic test\r\n"))
            assertFalse(request.contains("secret") || request.contains("ignored"))
        }
    }
}
