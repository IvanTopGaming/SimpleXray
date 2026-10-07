package com.simplexray.an.core.config

import com.google.gson.JsonParser
import com.simplexray.an.core.config.ownership.OwnedConfig
import org.junit.Assert.*
import org.junit.Test

class OwnedConfigTest {
    private val server =
        """{"tag":"server","protocol":"vless","settings":{"vnext":[{"address":"edge.example","port":443,"users":[{"id":"00000000-0000-0000-0000-000000000001","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"front.example","publicKey":"public-key","shortId":"abcd","fingerprint":"chrome"}}}"""

    @Test
    fun legacyChainAcceptsNullStreamBeforeApplyingRawDefaults() {
        val source =
            """{"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"address":"127.0.0.1","port":1080},"streamSettings":null,"proxySettings":{"tag":"hop"}},{"tag":"hop","protocol":"socks","settings":{"address":"127.0.0.1","port":1081}}]}"""
        val root =
            com.google.gson.JsonParser.parseString(OwnedConfig.build(source, "127.0.0.1", 10808))
                .asJsonObject
        val stream =
            root.getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("streamSettings")
        assertEquals("raw", stream["network"].asString)
        assertEquals("__sx_chain_1", stream.getAsJsonObject("sockopt")["dialerProxy"].asString)
    }

    @Test
    fun providerServicesAreDiscardedWhileServerTransportAndCredentialsSurvive() {
        val source =
            """{"remarks":"Provider","log":{"access":"/tmp/provider.log"},"api":{"tag":"api"},"stats":{},"policy":{},"reverse":{},"observatory":{},"burstObservatory":{},"fakedns":[],"dns":{"servers":["9.9.9.9"]},"routing":{"rules":[{"outboundTag":"direct"}]},"inbounds":[{"port":9999,"protocol":"http"}],"outbounds":[$server,{"tag":"direct","protocol":"freedom"}]}"""
        val result =
            JsonParser.parseString(
                    OwnedConfig.build(
                        source,
                        "127.0.0.2",
                        12345,
                        "app-user",
                        "app-pass",
                        listOf("1.1.1.1"),
                    )
                )
                .asJsonObject
        assertEquals(setOf("inbounds", "outbounds", "dns", "routing"), result.keySet())
        val inbound = result.getAsJsonArray("inbounds").single().asJsonObject
        assertEquals("127.0.0.2", inbound["listen"].asString)
        assertEquals(12345, inbound["port"].asInt)
        assertEquals("socks", inbound["protocol"].asString)
        assertEquals("password", inbound.getAsJsonObject("settings")["auth"].asString)
        assertEquals(
            "app-user",
            inbound
                .getAsJsonObject("settings")
                .getAsJsonArray("accounts")
                .single()
                .asJsonObject["user"]
                .asString,
        )
        assertEquals(
            "app-pass",
            inbound
                .getAsJsonObject("settings")
                .getAsJsonArray("accounts")
                .single()
                .asJsonObject["pass"]
                .asString,
        )
        val outbound = result.getAsJsonArray("outbounds").single().asJsonObject
        assertEquals("proxy", outbound["tag"].asString)
        assertEquals(JsonParser.parseString(server).asJsonObject["settings"], outbound["settings"])
        assertEquals(
            JsonParser.parseString(server).asJsonObject["streamSettings"],
            outbound["streamSettings"],
        )
        assertEquals(
            listOf("1.1.1.1"),
            result.getAsJsonObject("dns").getAsJsonArray("servers").map { it.asString },
        )
        val dnsRule =
            result.getAsJsonObject("routing").getAsJsonArray("rules").single().asJsonObject
        assertEquals("proxy", dnsRule["outboundTag"].asString)
        assertEquals(listOf("__sx_dns"), dnsRule.getAsJsonArray("inboundTag").map { it.asString })
    }

    @Test
    fun chainRootIsSelectedAndDependenciesAreKeptWithRewrittenReferences() {
        val source =
            """{"outbounds":[{"tag":"hop","protocol":"socks","settings":{"servers":[{"address":"hop.example","port":1080}]}},{"tag":"entry","protocol":"trojan","settings":{"servers":[{"address":"edge.example","port":443,"password":"secret"}]},"proxySettings":{"tag":"hop","transportLayer":true},"streamSettings":{"security":"tls","tlsSettings":{"serverName":"edge.example"}}},{"tag":"unused","protocol":"freedom"}]}"""
        val outbounds =
            JsonParser.parseString(OwnedConfig.build(source, "127.0.0.1", 10808))
                .asJsonObject
                .getAsJsonArray("outbounds")
        assertEquals(2, outbounds.size())
        assertEquals("trojan", outbounds[0].asJsonObject["protocol"].asString)
        assertEquals("proxy", outbounds[0].asJsonObject["tag"].asString)
        assertEquals(
            outbounds[1].asJsonObject["tag"],
            outbounds[0]
                .asJsonObject
                .getAsJsonObject("streamSettings")
                .getAsJsonObject("sockopt")["dialerProxy"],
        )
        assertFalse(outbounds[0].asJsonObject.has("proxySettings"))
    }

    @Test
    fun proxyTaggedDependencyCannotReplaceTheRootOfAnExistingProfile() {
        val source =
            """{"outbounds":[{"tag":"entry","protocol":"vless","settings":{},"proxySettings":{"tag":"proxy"}},{"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"hop.example","port":1080}]}}]}"""
        val outputs =
            JsonParser.parseString(OwnedConfig.build(source, "127.0.0.1", 10808))
                .asJsonObject
                .getAsJsonArray("outbounds")
        assertEquals(2, outputs.size())
        assertEquals("vless", outputs[0].asJsonObject["protocol"].asString)
        assertEquals("proxy", outputs[0].asJsonObject["tag"].asString)
        assertEquals(
            outputs[1].asJsonObject["tag"],
            outputs[0]
                .asJsonObject
                .getAsJsonObject("streamSettings")
                .getAsJsonObject("sockopt")["dialerProxy"],
        )
    }

    @Test
    fun legacyProtocolLayerChainPreservesRawTlsAndStoredSource() {
        val source =
            """{"outbounds":[{"tag":"entry","protocol":"socks","settings":{"address":"edge.test","port":1080},"proxySettings":{"tag":"hop"},"streamSettings":{"network":"xhttp","security":"tls","tlsSettings":{"serverName":"edge.test","fingerprint":"chrome"},"sockopt":{"dialerProxy":"ignored","mark":77},"xhttpSettings":{"downloadSettings":{"address":"inactive.test","sockopt":{"dialerProxy":"ignored"}}}}},{"tag":"hop","protocol":"socks","settings":{"address":"hop.test","port":1080}},{"tag":"ignored","protocol":"socks","settings":{"address":"ignored.test","port":1080}}]}"""
        val imported = OwnedConfig.importProfiles(source).single().second
        assertEquals(imported, OwnedConfig.serverProfile(source))
        assertEquals(imported, OwnedConfig.serverProfile(imported))
        val stored =
            JsonParser.parseString(imported)
                .asJsonObject
                .getAsJsonArray("outbounds")[0]
                .asJsonObject
        assertTrue(stored.has("proxySettings"))
        assertEquals("xhttp", stored.getAsJsonObject("streamSettings")["network"].asString)
        val runtime =
            JsonParser.parseString(OwnedConfig.build(imported, "127.0.0.1", 10808)).asJsonObject
        val outbound = runtime.getAsJsonArray("outbounds")[0].asJsonObject
        assertFalse(outbound.has("proxySettings"))
        val stream = outbound.getAsJsonObject("streamSettings")
        assertEquals("raw", stream["network"].asString)
        assertEquals("tls", stream["security"].asString)
        assertEquals("edge.test", stream.getAsJsonObject("tlsSettings")["serverName"].asString)
        assertEquals("unsafe", stream.getAsJsonObject("tlsSettings")["fingerprint"].asString)
        assertEquals(setOf("dialerProxy"), stream.getAsJsonObject("sockopt").keySet())
        assertEquals("__sx_chain_1", stream.getAsJsonObject("sockopt")["dialerProxy"].asString)
        assertFalse(stream.has("xhttpSettings"))
        assertEquals(imported, OwnedConfig.importProfiles(source).single().second)
    }

    @Test
    fun partialSocksCredentialsNeverEnableAuthenticationOnlyOnTheCore() {
        for ((username, password) in
            listOf(
                "" to "",
                "alice" to "",
                "" to "secret",
                " " to "",
                " " to "secret",
                "alice" to " ",
                "alice" to "secret",
            )) {
            val settings =
                JsonParser.parseString(
                        OwnedConfig.build(server, "127.0.0.1", 10808, username, password)
                    )
                    .asJsonObject
                    .getAsJsonArray("inbounds")
                    .single()
                    .asJsonObject
                    .getAsJsonObject("settings")
            val authenticated = username.isNotEmpty() && password.isNotEmpty()
            assertEquals(if (authenticated) "password" else "noauth", settings["auth"].asString)
            assertEquals(authenticated, settings.has("accounts"))
        }
    }

    @Test
    fun dialerProxyDependenciesSurviveTransportNesting() {
        val source =
            """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{},"streamSettings":{"network":"xhttp","sockopt":{"dialerProxy":"hop"},"xhttpSettings":{"extra":{"downloadSettings":{"sockopt":{"dialerProxy":"hop"}}}}}},{"tag":"hop","protocol":"socks","settings":{}}]}"""
        val outbounds =
            JsonParser.parseString(OwnedConfig.build(source, "127.0.0.1", 10808))
                .asJsonObject
                .getAsJsonArray("outbounds")
        val stream = outbounds[0].asJsonObject.getAsJsonObject("streamSettings")
        assertEquals(
            outbounds[1].asJsonObject["tag"],
            stream.getAsJsonObject("sockopt")["dialerProxy"],
        )
        assertEquals(
            outbounds[1].asJsonObject["tag"],
            stream
                .getAsJsonObject("xhttpSettings")
                .getAsJsonObject("extra")
                .getAsJsonObject("downloadSettings")
                .getAsJsonObject("sockopt")["dialerProxy"],
        )
    }

    @Test
    fun endpointBootstrapDoesNotDependOnTheProxyBeingResolved() {
        val source =
            """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{},"streamSettings":{"sockopt":{"domainStrategy":"ForceIP","tcpFastOpen":true},"xhttpSettings":{"extra":{"downloadSettings":{"sockopt":{"domainStrategy":"ForceIPv4"}}}}}}]}"""
        val outbound =
            JsonParser.parseString(OwnedConfig.build(source, "127.0.0.1", 10808))
                .asJsonObject
                .getAsJsonArray("outbounds")
                .single()
                .asJsonObject
        val stream = outbound.getAsJsonObject("streamSettings")
        assertEquals("AsIs", stream.getAsJsonObject("sockopt")["domainStrategy"].asString)
        assertTrue(stream.getAsJsonObject("sockopt")["tcpFastOpen"].asBoolean)
        assertEquals(
            "AsIs",
            stream
                .getAsJsonObject("xhttpSettings")
                .getAsJsonObject("extra")
                .getAsJsonObject("downloadSettings")
                .getAsJsonObject("sockopt")["domainStrategy"]
                .asString,
        )
    }

    @Test
    fun directDependencyDoesNotResolveTheServerThroughItsOwnProxy() {
        val source =
            """{"outbounds":[{"protocol":"vless","tag":"proxy","settings":{},"proxySettings":{"tag":"direct"}},{"protocol":"freedom","tag":"direct","settings":{"domainStrategy":"UseIP"}}]}"""
        val outbounds =
            JsonParser.parseString(OwnedConfig.build(source, "127.0.0.1", 10808))
                .asJsonObject
                .getAsJsonArray("outbounds")
        assertEquals(
            "AsIs",
            outbounds[1]
                .asJsonObject
                .getAsJsonObject("streamSettings")
                .getAsJsonObject("sockopt")["domainStrategy"]
                .asString,
        )
    }

    @Test
    fun ambiguousMissingCyclicAndUnsupportedDependenciesAreRejected() {
        val invalid =
            listOf(
                """{"outbounds":[$server,{"protocol":"socks","settings":{}}]}""",
                """{"outbounds":[{"protocol":"socks","tag":"proxy","proxySettings":{"tag":"missing"}}]}""",
                """{"outbounds":[{"protocol":"socks","tag":"proxy","proxySettings":{"tag":"hop"}},{"protocol":"socks","tag":"hop","proxySettings":{"tag":"proxy"}}]}""",
                """{"outbounds":[{"protocol":"socks","tag":"proxy","proxySettings":{"tag":"hop"}},{"protocol":"loopback","tag":"hop","settings":{"inboundTag":"provider"}}]}""",
                """{"outbounds":[{"protocol":"freedom","tag":"direct"}]}""",
                """{"outbounds":[{"protocol":"socks","tag":"same"},{"protocol":"http","tag":"same"}]}""",
                """{"transport":{"tcpSettings":{"header":{"type":"http"}}},"outbounds":[$server]}""",
            )
        invalid.forEach { source ->
            try {
                OwnedConfig.build(source, "127.0.0.1", 10808)
                fail("Accepted unsupported configuration: $source")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun multipleJsonServersBecomeIndependentProfilesWithoutProviderPolicy() {
        val profiles =
            OwnedConfig.importProfiles(
                """{"remarks":"Provider","dns":{"servers":["9.9.9.9"]},"outbounds":[$server,{"tag":"NL","protocol":"trojan","settings":{"servers":[{"address":"nl.example","port":443,"password":"secret"}]}},{"tag":"direct","protocol":"freedom"}]}"""
            )
        assertEquals(listOf("server", "NL"), profiles.map { it.first })
        profiles.forEach { (_, source) ->
            val root = JsonParser.parseString(source).asJsonObject
            assertEquals(setOf("outbounds"), root.keySet())
            assertEquals(1, root.getAsJsonArray("outbounds").size())
        }
    }

    @Test
    fun jsonArrayAndRawOutboundImportPreserveProfileNamesAndChain() {
        val profiles =
            OwnedConfig.importProfiles(
                """[{"remarks":"Paris","outbounds":[$server]}, {"name":"London","protocol":"trojan","settings":{"servers":[{"address":"ldn.example","port":443,"password":"secret"}]}}]"""
            )
        assertEquals(listOf("Paris", "London"), profiles.map { it.first })
        assertEquals(
            "trojan",
            JsonParser.parseString(profiles[1].second)
                .asJsonObject
                .getAsJsonArray("outbounds")
                .single()
                .asJsonObject["protocol"]
                .asString,
        )
    }
}
