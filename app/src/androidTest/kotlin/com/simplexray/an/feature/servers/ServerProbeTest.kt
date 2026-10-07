package com.simplexray.an.feature.servers

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import com.simplexray.an.core.network.probe.ServerProbe
import com.simplexray.an.core.network.probe.buildProbeConfig
import com.simplexray.an.feature.servers.model.ProbeMethod
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerProbeTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val direct = """{"outbounds":[{"tag":"proxy","protocol":"freedom"}]}"""

    @Test
    fun isolatedConfigRemovesListenersAndForcesProxyWithoutChangingOriginal() {
        val original =
            """{"api":{"tag":"api"},"observatory":{},"reverse":{},"log":{"access":"/tmp/secret"},"dns":{"servers":["localhost"]},"inbounds":[{"port":10808}],"outbounds":[{"tag":"direct","protocol":"freedom"},{"tag":"proxy","protocol":"blackhole"}],"routing":{"rules":[{"outboundTag":"direct","network":"tcp"}]}}"""
        val built = JSONObject(buildProbeConfig(original, 12345, "user", "pass"))
        val inbound = built.getJSONArray("inbounds").getJSONObject(0)
        assertEquals(1, built.getJSONArray("inbounds").length())
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals(12345, inbound.getInt("port"))
        assertEquals("http", inbound.getString("protocol"))
        assertEquals(
            "pass",
            inbound
                .getJSONObject("settings")
                .getJSONArray("accounts")
                .getJSONObject(0)
                .getString("pass"),
        )
        assertEquals(
            "proxy",
            built
                .getJSONObject("routing")
                .getJSONArray("rules")
                .getJSONObject(0)
                .getString("outboundTag"),
        )
        assertFalse(built.has("api") || built.has("observatory") || built.has("reverse"))
        assertEquals("none", built.getJSONObject("log").getString("loglevel"))
        assertTrue(built.has("dns"))
        assertEquals(
            10808,
            JSONObject(original).getJSONArray("inbounds").getJSONObject(0).getInt("port"),
        )
    }

    @Test
    fun realCoreReachesHttpTargetAndPreservesQuery() = runBlocking {
        ProbeHttpFixture().use { target ->
            val before = corePids()
            val result = ServerProbe(app).check(direct, target.url + "/check?q=kept", 1500)
            assertNotNull(result.latencyMs)
            assertNull(result.error)
            assertTrue(target.request.get().contains("/check?q=kept"))
            assertEquals(before, corePids())
        }
    }

    @Test
    fun originalDirectRoutingCannotHideBrokenProxy() = runBlocking {
        ProbeHttpFixture().use { target ->
            val config =
                """{"outbounds":[{"tag":"direct","protocol":"freedom"},{"tag":"proxy","protocol":"blackhole"}],"routing":{"rules":[{"network":"tcp","outboundTag":"direct"}]}}"""
            assertNull(ServerProbe(app).check(config, target.url, 400).latencyMs)
            assertEquals("", target.request.get())
        }
    }

    @Test
    fun headReachesHttpTargetThroughCoreWithoutChangingDefaultGet() = runBlocking {
        for ((method, verb) in
            listOf(ProbeMethod.HTTP_GET to "GET", ProbeMethod.HTTP_HEAD to "HEAD")) {
            ProbeHttpFixture().use { target ->
                val result =
                    ServerProbe(app).check(direct, target.url + "/check?q=kept", 1500, method)
                assertNotNull(result.latencyMs)
                assertTrue(target.request.get().startsWith("$verb /check?q=kept HTTP/1.1"))
            }
        }
    }

    @Test
    fun tcpChecksOutboundPortWithoutStartingCoreOrUsingTargetUrl() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val before = corePids()
            val config =
                """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":${server.localPort}}]}}]}"""
            val result = ServerProbe(app).check(config, "unused", 1000, ProbeMethod.TCP)
            assertNotNull(result.latencyMs)
            assertNull(result.error)
            assertEquals(before, corePids())
            server.accept().use { socket -> assertEquals(-1, socket.getInputStream().read()) }
        }
    }

    @Test
    fun httpThroughCoreSupportsHostnamesDisallowedByAndroidCleartextPolicy() = runBlocking {
        ProbeHttpFixture().use { target ->
            val config =
                """{"dns":{"hosts":{"probe-target.invalid":"127.0.0.1"}},"outbounds":[{"tag":"proxy","protocol":"freedom","settings":{"domainStrategy":"UseIP"}}]}"""
            val result =
                ServerProbe(app)
                    .check(config, target.url.replace("127.0.0.1", "probe-target.invalid"), 1000)
            assertNotNull(
                "HTTP target must be reached through Xray, not blocked as a direct Android request",
                result.latencyMs,
            )
            assertTrue(target.request.get().isNotEmpty())
        }
    }

    @Test
    fun proxyHostnameBootstrapKeepsItsOriginalDirectDnsRoute() = runBlocking {
        val id = "11111111-1111-1111-1111-111111111111"
        val port = ServerSocket(0).use { it.localPort }
        val serverConfig =
            """{"log":{"loglevel":"none"},"inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"vless","settings":{"clients":[{"id":"$id"}],"decryption":"none"}}],"outbounds":[{"protocol":"freedom"}]}"""
        val core =
            ProcessBuilder(File(app.applicationInfo.nativeLibraryDir, "libxray.so").path)
                .redirectErrorStream(true)
                .redirectOutput(File("/dev/null"))
                .start()
        try {
            core.outputStream.use { it.write(serverConfig.toByteArray()) }
            withTimeout(5000) {
                while (
                    runCatching {
                        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 50) }
                    }
                        .isFailure
                ) delay(25)
            }
            ProbeDnsFixture().use { dns ->
                ProbeHttpFixture().use { target ->
                    val config =
                        """{"dns":{"servers":[{"address":"127.0.0.1","port":${dns.port}}],"queryStrategy":"UseIPv4"},"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"proxy.test","port":$port,"users":[{"id":"$id","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"none","sockopt":{"domainStrategy":"ForceIP"}}},{"tag":"direct","protocol":"freedom"}],"routing":{"rules":[{"type":"field","ip":["127.0.0.1"],"outboundTag":"direct"}]}}"""
                    assertNotNull(
                        "DNS bootstrap must not loop through the unresolved proxy",
                        ServerProbe(app).check(config, target.url, 1500).latencyMs,
                    )
                    assertTrue(dns.requested.await(1, TimeUnit.SECONDS))
                }
            }
        } finally {
            core.destroyForcibly()
            assertTrue(core.waitFor(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun redirectAndHttpFailureAreNotReportedAsAvailable() = runBlocking {
        for (status in listOf(302, 503)) ProbeHttpFixture(status).use { target ->
            assertNull(ServerProbe(app).check(direct, target.url, 1000).latencyMs)
            assertTrue(target.request.get().isNotEmpty())
        }
    }

    @Test
    fun invalidConfigAndTargetFailWithoutLeakingCoreProcesses() = runBlocking {
        val before = corePids()
        assertNull(ServerProbe(app).check("not json", "http://127.0.0.1", 300).latencyMs)
        assertNull(ServerProbe(app).check(direct, "file:///secret", 300).latencyMs)
        assertEquals(before, corePids())
    }

    @Test
    fun timeoutAndCancellationReapOwnedProcesses() = runBlocking {
        val before = corePids()
        ProbeHttpFixture(block = true).use { target ->
            assertNull(ServerProbe(app).check(direct, target.url, 300).latencyMs)
            assertTrue(target.requested.await(1, TimeUnit.SECONDS))
            assertEquals(before, corePids())
        }
        ProbeHttpFixture(block = true).use { target ->
            val job = async(Dispatchers.IO) { ServerProbe(app).check(direct, target.url, 10000) }
            assertTrue(target.requested.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(before, corePids())
        }
    }
}

internal class ProbeHttpFixture(private val status: Int = 204, private val block: Boolean = false) :
    AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${server.localPort}"
    val request = AtomicReference("")
    val requested = CountDownLatch(1)
    private val release = CountDownLatch(1)

    init {
        thread(isDaemon = true, name = "probe-http-fixture") {
            while (!server.isClosed) runCatching {
                val socket = server.accept()
                thread(isDaemon = true) {
                    runCatching {
                        socket.use {
                            it.soTimeout = 5000
                            val reader = it.getInputStream().bufferedReader()
                            request.set(reader.readLine().orEmpty())
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            requested.countDown()
                            if (block) release.await(10, TimeUnit.SECONDS)
                            it.getOutputStream()
                                .write(
                                    "HTTP/1.1 $status Fixture\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                        .toByteArray()
                                )
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        release.countDown()
        server.close()
    }
}

internal fun corePids(): Set<String> {
    val process = ProcessBuilder("/system/bin/ps", "-A", "-o", "PID,NAME").start()
    return process.inputStream
        .bufferedReader()
        .use { reader ->
            reader
                .readLines()
                .filter { it.contains("libxray.so") }
                .map { it.trim().substringBefore(' ') }
                .toSet()
        }
        .also {
            process.waitFor(2, TimeUnit.SECONDS)
            process.destroy()
        }
}

internal class ProbeDnsFixture : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    val port = socket.localPort
    val requested = CountDownLatch(1)

    init {
        thread(isDaemon = true, name = "probe-dns-fixture") {
            while (!socket.isClosed) runCatching {
                val packet = DatagramPacket(ByteArray(2048), 2048)
                socket.receive(packet)
                requested.countDown()
                var end = 12
                while (packet.data[end].toInt() != 0) end += 1 + (packet.data[end].toInt() and 255)
                end += 5
                val response =
                    packet.data.copyOf(end) +
                        byteArrayOf(-64, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 127, 0, 0, 1)
                response[2] = -127
                response[3] = -128
                response[6] = 0
                response[7] = 1
                response[8] = 0
                response[9] = 0
                response[10] = 0
                response[11] = 0
                socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
            }
        }
    }

    override fun close() {
        socket.close()
    }
}
