package com.simplexray.an.feature.kernel

import com.simplexray.an.feature.kernel.model.KernelSettings
import com.simplexray.an.feature.kernel.model.ServerDomainStrategy
import com.simplexray.an.feature.kernel.model.SniffProtocol
import com.simplexray.an.feature.kernel.model.TcpCongestion
import com.simplexray.an.feature.kernel.model.Udp443Mode
import org.junit.Assert.*
import org.junit.Test

class KernelSettingsTest {
    @Test
    fun defaultsAndAllOptionsRoundTrip() {
        assertEquals(KernelSettings(), KernelSettings.decode(null))
        assertEquals(KernelSettings(), KernelSettings.decode("{}"))
        val settings =
            KernelSettings(
                sniffingEnabled = false,
                sniffingRouteOnly = false,
                sniffingProtocols = setOf(SniffProtocol.TLS),
                muxEnabled = true,
                muxConcurrency = -1,
                xudpConcurrency = 1024,
                udp443 = Udp443Mode.SKIP,
                serverDomainStrategy = ServerDomainStrategy.USE_IPV6V4,
                tcpFastOpen = true,
                tcpKeepAliveInterval = 3600,
                tcpUserTimeout = 600000,
                tcpCongestion = TcpCongestion.BBR,
                handshake = 600,
                connectionIdle = 86400,
                uplinkOnly = 0,
                downlinkOnly = 600,
                bufferSize = 65536,
            )
        assertEquals(settings, KernelSettings.decode(settings.encode()))
        assertEquals(settings.encode(), KernelSettings.decode(settings.encode()).encode())
        assertEquals(
            emptySet<SniffProtocol>(),
            KernelSettings.decode(KernelSettings(sniffingProtocols = emptySet()).encode())
                .sniffingProtocols,
        )
    }

    @Test
    fun invalidRangesAndMalformedTypesAreRejected() {
        listOf(
                KernelSettings(muxConcurrency = -2),
                KernelSettings(muxConcurrency = 129),
                KernelSettings(xudpConcurrency = -2),
                KernelSettings(xudpConcurrency = 1025),
                KernelSettings(tcpKeepAliveInterval = -1),
                KernelSettings(tcpKeepAliveInterval = 3601),
                KernelSettings(tcpUserTimeout = -1),
                KernelSettings(tcpUserTimeout = 600001),
                KernelSettings(handshake = 0),
                KernelSettings(handshake = 601),
                KernelSettings(connectionIdle = 0),
                KernelSettings(connectionIdle = 86401),
                KernelSettings(uplinkOnly = -1),
                KernelSettings(uplinkOnly = 601),
                KernelSettings(downlinkOnly = -1),
                KernelSettings(downlinkOnly = 601),
                KernelSettings(bufferSize = -1),
                KernelSettings(bufferSize = 65537),
            )
            .forEach { assertThrows(IllegalArgumentException::class.java) { it.validate() } }
        listOf(
                "null",
                "[]",
                "oops",
                "{\"muxEnabled\":1}",
                "{\"muxConcurrency\":1.5}",
                "{\"muxConcurrency\":\"1\"}",
                "{\"muxConcurrency\":2147483648}",
                "{\"handshake\":0}",
                "{\"sniffingProtocols\":[\"BOGUS\"]}",
                "{\"tcpCongestion\":\"reno\"}",
            )
            .forEach {
                assertThrows(it, IllegalArgumentException::class.java) { KernelSettings.decode(it) }
            }
    }

    @Test
    fun nullableLimitsRetainExplicitZero() {
        val value = KernelSettings.decode("{\"handshake\":null,\"uplinkOnly\":0,\"bufferSize\":0}")
        assertNull(value.handshake)
        assertEquals(0, value.uplinkOnly)
        assertEquals(0, value.bufferSize)
    }
}
