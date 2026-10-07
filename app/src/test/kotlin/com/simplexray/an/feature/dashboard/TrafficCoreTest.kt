package com.simplexray.an.feature.dashboard

import android.app.Application
import com.simplexray.an.core.config.ConfigUtils
import com.simplexray.an.core.runtime.stats.CoreStatsClient
import com.simplexray.an.feature.dashboard.model.TrafficState
import java.io.DataInputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TrafficCoreTest {
    @Test
    fun directTrafficCountsClientBytesAndExcludesOtherInbounds() {
        verify("direct")
    }

    @Test
    fun bothOwnedInboundCountersAreAddedExactlyOnce() {
        verify("secondClient")
    }

    @Test
    fun singleProxyCountsClientBytesOnce() {
        verify("single")
    }

    @Test
    fun rawProtocolChainCountsClientBytesOnce() {
        verify("rawProtocolChain")
    }

    @Test
    fun dialerProxyChainCountsClientBytesOnce() {
        verify("dialerProxy")
    }

    @Test
    fun dnsTransportShapedChainCountsClientBytesOnce() {
        verify("dnsTransport")
    }

    @Test
    fun udpCountsEachSocksDatagramOnceAfterTcpAssociation() = runBlocking {
        val core = System.getenv("SIMPLEXRAY_TEST_CORE")?.let(::File)
        assumeTrue("Host Xray must be explicitly supplied", core?.canExecute() == true)
        val dir = Files.createTempDirectory("traffic-udp").toFile()
        val address = InetAddress.getByName("127.0.0.1")
        val reserved = List(2) { ServerSocket(0, 1, address) }
        val (clientPort, apiPort) = reserved.map { it.localPort }
        reserved.forEach { it.close() }
        val upload = ByteArray(1200) { (it % 251).toByte() }
        val download = ByteArray(700) { (it % 239).toByte() }
        val server = DatagramSocket(0, address).apply { soTimeout = 5000 }
        val response = FutureTask {
            val packet = DatagramPacket(ByteArray(2048), 2048)
            server.receive(packet)
            assertArrayEquals(
                upload,
                packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
            )
            server.send(DatagramPacket(download, download.size, packet.socketAddress))
        }
        val responder = thread(isDaemon = true, name = "traffic-udp-peer") { response.run() }
        var child: Process? = null
        try {
            val config =
                File(dir, "config.json").apply {
                    writeText(
                        ConfigUtils.injectStatsService(
                            "127.0.0.1",
                            apiPort,
                            """{"inbounds":[{"tag":"__sx_client","listen":"127.0.0.1","port":$clientPort,"protocol":"socks","settings":{"auth":"noauth","udp":true}}],"outbounds":[{"tag":"direct","protocol":"freedom"}]}""",
                        )
                    )
                }
            val log = File(dir, "core.log")
            child =
                ProcessBuilder(requireNotNull(core).path, "run", "-c", config.path)
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .apply { environment().remove("XRAY_MPH_CACHE") }
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
                assertTrue("Core failed to start: ${log.readText()}", ready)
                assertEquals(TrafficState(0L, 0L), stats.getTraffic())
                Socket().use { control ->
                    control.connect(InetSocketAddress(address, clientPort), 2000)
                    control.soTimeout = 5000
                    val input = DataInputStream(control.getInputStream())
                    val output = control.getOutputStream()
                    output.write(byteArrayOf(5, 1, 0))
                    assertEquals(5, input.readUnsignedByte())
                    assertEquals(0, input.readUnsignedByte())
                    output.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
                    val reply = ByteArray(10)
                    input.readFully(reply)
                    assertEquals(5, reply[0].toInt())
                    assertEquals(0, reply[1].toInt())
                    assertEquals(1, reply[3].toInt())
                    val relay =
                        InetSocketAddress(
                            InetAddress.getByAddress(reply.copyOfRange(4, 8)),
                            ((reply[8].toInt() and 255) shl 8) or (reply[9].toInt() and 255),
                        )
                    assertTraffic(stats, TrafficState(13L, 12L))
                    val baseline = requireNotNull(stats.getTraffic())
                    DatagramSocket(0, address).use { socket ->
                        socket.soTimeout = 5000
                        val header =
                            byteArrayOf(
                                0,
                                0,
                                0,
                                1,
                                127,
                                0,
                                0,
                                1,
                                (server.localPort shr 8).toByte(),
                                server.localPort.toByte(),
                            )
                        val packet = header + upload
                        socket.send(DatagramPacket(packet, packet.size, relay))
                        val received = DatagramPacket(ByteArray(2048), 2048)
                        socket.receive(received)
                        assertArrayEquals(
                            header + download,
                            received.data.copyOfRange(
                                received.offset,
                                received.offset + received.length,
                            ),
                        )
                    }
                    response.get(5, TimeUnit.SECONDS)
                    val expected = TrafficState(baseline.uplink + 1210L, baseline.downlink + 710L)
                    assertTraffic(stats, expected)
                    val actual = requireNotNull(stats.getTraffic())
                    assertEquals(
                        TrafficState(1210L, 710L),
                        TrafficState(
                            actual.uplink - baseline.uplink,
                            actual.downlink - baseline.downlink,
                        ),
                    )
                    assertEquals(expected, stats.getTraffic())
                }
            }
        } finally {
            child?.let {
                it.destroy()
                if (!it.waitFor(1, TimeUnit.SECONDS)) {
                    it.destroyForcibly()
                    it.waitFor(1, TimeUnit.SECONDS)
                }
            }
            server.close()
            responder.join(1000)
            dir.deleteRecursively()
        }
    }

    private fun verify(mode: String) = runBlocking {
        val core = System.getenv("SIMPLEXRAY_TEST_CORE")?.let(::File)
        assumeTrue("Host Xray must be explicitly supplied", core?.canExecute() == true)
        val dir = Files.createTempDirectory("traffic-core").toFile()
        val reserved = List(3) { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) }
        val (clientPort, hopPort, apiPort) = reserved.map { it.localPort }
        reserved.forEach { it.close() }
        val upload = ByteArray(65536) { (it % 251).toByte() }
        val download = ByteArray(32768) { (it % 239).toByte() }
        val expected = TrafficState(65549L, 32780L)
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5000
        val response = FutureTask {
            repeat(2) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val received = ByteArray(upload.size)
                    DataInputStream(socket.getInputStream()).readFully(received)
                    assertArrayEquals(upload, received)
                    socket.getOutputStream().write(download)
                }
            }
        }
        val responder = thread(isDaemon = true, name = "traffic-peer") { response.run() }
        var child: Process? = null
        try {
            val config =
                File(dir, "config.json").apply {
                    writeText(
                        ConfigUtils.injectStatsService(
                            "127.0.0.1",
                            apiPort,
                            config(mode, clientPort, hopPort),
                        )
                    )
                }
            val log = File(dir, "core.log")
            child =
                ProcessBuilder(requireNotNull(core).path, "run", "-c", config.path)
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .apply { environment().remove("XRAY_MPH_CACHE") }
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
                assertTrue("Core failed to start: ${log.readText()}", ready)
                assertEquals(TrafficState(0L, 0L), stats.getTraffic())
                exchange(clientPort, server.localPort, upload, download)
                assertTraffic(stats, expected)
                exchange(hopPort, server.localPort, upload, download)
                response.get(5, TimeUnit.SECONDS)
                val combined =
                    if (mode == "secondClient")
                        TrafficState(expected.uplink * 2, expected.downlink * 2)
                    else expected
                assertTraffic(stats, combined)
                assertEquals("Reading counters must not reset them", combined, stats.getTraffic())
            }
        } finally {
            child?.let {
                it.destroy()
                if (!it.waitFor(1, TimeUnit.SECONDS)) {
                    it.destroyForcibly()
                    it.waitFor(1, TimeUnit.SECONDS)
                }
            }
            server.close()
            responder.join(1000)
            dir.deleteRecursively()
        }
    }

    private suspend fun assertTraffic(client: CoreStatsClient, expected: TrafficState) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var actual = client.getTraffic()
        while (actual != expected && System.nanoTime() < deadline) {
            delay(20)
            actual = client.getTraffic()
        }
        assertEquals(expected, actual)
    }

    private fun exchange(port: Int, destination: Int, upload: ByteArray, download: ByteArray) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
            socket.soTimeout = 5000
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            output.write(byteArrayOf(5, 1, 0))
            assertEquals(5, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedByte())
            output.write(
                byteArrayOf(
                    5,
                    1,
                    0,
                    1,
                    127,
                    0,
                    0,
                    1,
                    (destination shr 8).toByte(),
                    destination.toByte(),
                )
            )
            val reply = ByteArray(10)
            input.readFully(reply)
            assertEquals(0, reply[1].toInt())
            output.write(upload)
            val received = ByteArray(download.size)
            input.readFully(received)
            assertArrayEquals(download, received)
            assertEquals(-1, input.read())
        }
    }

    private fun config(mode: String, clientPort: Int, hopPort: Int): String {
        val chain =
            when (mode) {
                "rawProtocolChain" ->
                    """, "streamSettings":{"network":"raw","sockopt":{"dialerProxy":"chain"}}"""
                "dialerProxy" -> """, "streamSettings":{"sockopt":{"dialerProxy":"chain"}}"""
                else -> ""
            }
        val target =
            when (mode) {
                "direct",
                "secondClient" -> "direct"
                "dnsTransport" -> "__sx_dns_transport"
                else -> "proxy"
            }
        val secondTag = if (mode == "secondClient") "__sx_http_client" else "__sx_client_extra"
        return """
            {
              "log":{"loglevel":"warning"},
              "inbounds":[
                {"tag":"__sx_client","listen":"127.0.0.1","port":$clientPort,"protocol":"socks","settings":{"auth":"noauth"}},
                {"tag":"$secondTag","listen":"127.0.0.1","port":$hopPort,"protocol":"socks","settings":{"auth":"noauth"}}
              ],
              "outbounds":[
                {"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":$hopPort}]}$chain},
                {"tag":"direct","protocol":"freedom"},
                {"tag":"chain","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":$hopPort}]}},
                {"tag":"__sx_dns_transport","protocol":"freedom","settings":{},"streamSettings":{"sockopt":{"domainStrategy":"ForceIP","dialerProxy":"proxy"}}}
              ],
              "routing":{"rules":[
                {"inboundTag":["$secondTag"],"outboundTag":"direct"},
                {"inboundTag":["__sx_client"],"outboundTag":"$target"}
              ]}
            }
        """
            .trimIndent()
    }
}
