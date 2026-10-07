package com.simplexray.an.feature.dns

import com.simplexray.an.feature.dns.model.DnsQueryStrategy
import com.simplexray.an.feature.dns.model.DnsSettings
import com.simplexray.an.feature.routing.model.RouteTarget
import org.junit.Assert.*
import org.junit.Test

class DnsSettingsTest {
    @Test
    fun defaultsAndSnapshotsPreserveEveryControl() {
        assertTrue(DnsSettings().fakeIpEnabled)
        assertEquals("77.88.8.8", DnsSettings.decode("{}").directDns)
        assertEquals("9.9.9.9", DnsSettings.decode(null, "9.9.9.9").primaryDns)
        val settings =
            DnsSettings(
                fakeIpEnabled = false,
                primaryDns = "https://dns.example/dns-query",
                primaryBootstrap = "1.2.3.4",
                fallbackEnabled = true,
                fallbackDns = "udp://[2001:db8::1]:5353",
                route = RouteTarget.DIRECT,
                queryStrategy = DnsQueryStrategy.IPV4,
                cacheEnabled = false,
                directDns = "https://direct.example/dns-query",
                directBootstrap = "77.88.8.8",
            )
        assertEquals(settings, DnsSettings.decode(settings.encode()))
        assertEquals(DnsSettings(primaryDns = "9.9.9.9"), DnsSettings.decode("{}", "9.9.9.9"))
    }

    @Test
    fun rejectsMalformedAndAmbiguousResolvers() {
        for (value in
            listOf(
                "",
                "localhost",
                "1.2.3.999",
                "https://user:pass@dns.example/query",
                "http://dns.example/query",
                "https://dns.example/query#fragment",
                "udp://dns.example",
                "udp://1.1.1.1:0",
                "1.1.1.1/path",
                "https://dns.example:99999/query",
            )) {
            assertThrows(value, IllegalArgumentException::class.java) {
                DnsSettings(primaryDns = value, primaryBootstrap = "1.1.1.1").validate()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsSettings(primaryDns = "https://dns.example/query").validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsSettings(primaryBootstrap = "resolver.example").validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsSettings(route = RouteTarget.BLOCK).validate()
        }
        for (raw in
            listOf(
                "null",
                "[]",
                "{\"fakeIpEnabled\":\"true\"}",
                "{\"route\":\"BLOCK\"}",
                "{\"queryStrategy\":\"bad\"}",
            )) {
            assertThrows(IllegalArgumentException::class.java) { DnsSettings.decode(raw) }
        }
    }

    @Test
    fun validatesDirectResolverAndBootstrap() {
        assertThrows(IllegalArgumentException::class.java) {
            DnsSettings(directDns = "https://direct.example/dns-query").validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsSettings(directBootstrap = "not-an-ip").validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsSettings.decode("""{"directDns":false}""")
        }
    }

    @Test
    fun permitsLiteralResolversAndOptionalInactiveFallback() {
        for (value in
            listOf(
                "8.8.8.8",
                "2001:4860:4860::8888",
                "127.0.0.1:5353",
                "udp://127.0.0.1:5353",
                "[::1]:5353",
                "https://1.1.1.1/dns-query",
            )) {
            DnsSettings(primaryDns = value).validate()
        }
        DnsSettings(fallbackEnabled = false, fallbackDns = "").validate()
        assertThrows(IllegalArgumentException::class.java) {
            DnsSettings(fallbackEnabled = true, fallbackDns = "").validate()
        }
    }
}
