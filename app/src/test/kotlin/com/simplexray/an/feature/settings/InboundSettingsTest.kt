package com.simplexray.an.feature.settings

import com.google.gson.JsonParser
import com.simplexray.an.core.config.dns.DnsConfigCompiler
import com.simplexray.an.core.config.inbound.InboundConfigCompiler
import com.simplexray.an.core.config.ownership.OwnedConfig
import com.simplexray.an.core.config.routing.RoutingCompiler
import com.simplexray.an.feature.dns.model.DnsSettings
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.settings.model.InboundSettings
import org.junit.Assert.*
import org.junit.Test

class InboundSettingsTest {
    private val source =
        """{"protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":12345}]}}"""

    @Test
    fun httpUsesSeparatePortAndSharesAuthentication() {
        val settings =
            InboundSettings(httpPort = 18080, socksUsername = "alice", socksPassword = "secret")
        val root =
            JsonParser.parseString(InboundConfigCompiler.configure(owned(), settings)).asJsonObject
        val http =
            root
                .getAsJsonArray("inbounds")
                .single { it.asJsonObject["protocol"].asString == "http" }
                .asJsonObject
        assertEquals(18080, http["port"].asInt)
        assertEquals("127.0.0.1", http["listen"].asString)
        assertEquals("__sx_http_client", http["tag"].asString)
        assertEquals(
            "alice",
            http
                .getAsJsonObject("settings")
                .getAsJsonArray("accounts")[0]
                .asJsonObject["user"]
                .asString,
        )
        val disabled =
            JsonParser.parseString(
                    InboundConfigCompiler.configure(
                        root.toString(),
                        settings.copy(httpProxyEnabled = false),
                    )
                )
                .asJsonObject
        assertEquals(1, disabled.getAsJsonArray("inbounds").size())
    }

    @Test
    fun duplicatePortsReject() {
        assertThrows(IllegalArgumentException::class.java) {
            InboundSettings(httpPort = 10808).validate()
        }
    }

    @Test
    fun disabledLanCannotExposeListenersAndWildcardDialUsesLoopback() {
        val disabled = InboundSettings(socksAddress = "0.0.0.0")
        val root =
            JsonParser.parseString(InboundConfigCompiler.configure(owned(), disabled)).asJsonObject
        assertTrue(
            root.getAsJsonArray("inbounds").all {
                it.asJsonObject["listen"].asString == "127.0.0.1"
            }
        )
        assertEquals("127.0.0.1", disabled.copy(allowLanAccess = true).connectAddress)
        assertEquals(
            "::1",
            disabled.copy(socksAddress = "::", allowLanAccess = true).connectAddress,
        )
    }

    @Test
    fun udpDisabledKeepsDnsAheadOfScopedTrafficBlock() {
        val settings = InboundSettings(socksUdpEnabled = false)
        val configured = InboundConfigCompiler.configure(owned(), settings)
        val compiled =
            RoutingCompiler.compile(
                DnsConfigCompiler.compile(configured, DnsSettings(fakeIpEnabled = false), false),
                RoutingSettings(),
            )
        val root =
            JsonParser.parseString(InboundConfigCompiler.applyTrafficPolicy(compiled, settings))
                .asJsonObject
        assertTrue(
            root
                .getAsJsonArray("inbounds")[0]
                .asJsonObject
                .getAsJsonObject("settings")["udp"]
                .asBoolean
        )
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        val block = rules.indexOfFirst { it["ruleTag"]?.asString == "__sx_user_udp_block" }
        val dns = rules.indexOfFirst { it["port"]?.asString == "53" }
        assertTrue(dns >= 0 && block > dns)
        assertTrue(
            rules.drop(block + 1).any {
                it.getAsJsonArray("inboundTag")?.any { tag -> tag.asString == "__sx_client" } ==
                    true
            }
        )
        assertEquals("udp", rules[block]["network"].asString)
        assertEquals("1-52,54-65535", rules[block]["port"].asString)
        assertEquals(
            setOf("__sx_client", "__sx_http_client"),
            rules[block].getAsJsonArray("inboundTag").map { it.asString }.toSet(),
        )
        val target = rules[block]["outboundTag"].asString
        assertEquals(
            "blackhole",
            root
                .getAsJsonArray("outbounds")
                .single { it.asJsonObject["tag"]?.asString == target }
                .asJsonObject["protocol"]
                .asString,
        )
        val enabled =
            JsonParser.parseString(
                    InboundConfigCompiler.applyTrafficPolicy(
                        root.toString(),
                        settings.copy(socksUdpEnabled = true),
                    )
                )
                .asJsonObject
        assertFalse(
            enabled.getAsJsonObject("routing").getAsJsonArray("rules").any {
                it.asJsonObject["ruleTag"]?.asString == "__sx_user_udp_block"
            }
        )
    }

    private fun owned() = OwnedConfig.build(source, "127.0.0.1", 10808)
}
