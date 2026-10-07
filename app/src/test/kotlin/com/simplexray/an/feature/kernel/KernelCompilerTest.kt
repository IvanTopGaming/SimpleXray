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
import com.simplexray.an.feature.kernel.model.Udp443Mode
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingRule
import com.simplexray.an.feature.routing.model.RoutingSettings
import org.junit.Assert.*
import org.junit.Test

class KernelCompilerTest {
    private val source =
        """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"server.test","port":443,"users":[{"id":"11111111-1111-1111-1111-111111111111","encryption":"none","flow":"xtls-rprx-vision","level":7}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"cover.test","publicKey":"key"},"sockopt":{"dialerProxy":"hop","domainStrategy":"UseIPv6","tcpFastOpen":true,"tcpKeepAliveInterval":5,"tcpCongestion":"bbr","tcpUserTimeout":7}},"mux":{"enabled":true,"concurrency":12}},{"tag":"hop","protocol":"socks","settings":{"servers":[{"address":"hop.test","port":1080}]},"mux":{"enabled":false},"streamSettings":{"sockopt":{"mark":77}}}]}"""

    private fun compile(
        settings: KernelSettings = KernelSettings(),
        fake: Boolean = false,
    ): JsonObject {
        val owned = OwnedConfig.build(source, "127.0.0.1", 1080)
        val dns = DnsConfigCompiler.compile(owned, DnsSettings(fakeIpEnabled = fake), false)
        return JsonParser.parseString(KernelConfigCompiler.compile(dns, settings)).asJsonObject
    }

    @Test
    fun defaultsReplaceOwnedFieldsAndPreserveSecurityAndChain() {
        val result = compile()
        val proxy = result.getAsJsonArray("outbounds")[0].asJsonObject
        assertFalse(proxy.getAsJsonObject("mux")["enabled"].asBoolean)
        val stream = proxy.getAsJsonObject("streamSettings")
        assertEquals("reality", stream["security"].asString)
        assertEquals("key", stream.getAsJsonObject("realitySettings")["publicKey"].asString)
        val socket = stream.getAsJsonObject("sockopt")
        assertEquals("__sx_chain_1", socket["dialerProxy"].asString)
        assertEquals("AsIs", socket["domainStrategy"].asString)
        assertTrue(socket["tcpFastOpen"].asBoolean)
        assertFalse(socket.has("tcpKeepAliveInterval"))
        assertFalse(socket.has("tcpCongestion"))
        assertFalse(socket.has("tcpUserTimeout"))
        val chain = result.getAsJsonArray("outbounds")[1].asJsonObject
        assertEquals(
            77,
            chain.getAsJsonObject("streamSettings").getAsJsonObject("sockopt")["mark"].asInt,
        )
        assertFalse(chain.getAsJsonObject("mux")["enabled"].asBoolean)
        assertEquals(
            "xtls-rprx-vision",
            proxy
                .getAsJsonObject("settings")
                .getAsJsonArray("vnext")[0]
                .asJsonObject
                .getAsJsonArray("users")[0]
                .asJsonObject["flow"]
                .asString,
        )
    }

    @Test
    fun disabledSnifferSurvivesDomainRulesButFakeIpRestorationRemains() {
        for (fake in listOf(false, true)) {
            val result =
                RoutingCompiler.compile(
                    compile(KernelSettings(sniffingEnabled = false), fake).toString(),
                    RoutingSettings(
                        rules = listOf(RoutingRule(name = "domain", values = listOf("example.com")))
                    ),
                )
            val sniffing =
                JsonParser.parseString(result)
                    .asJsonObject
                    .getAsJsonArray("inbounds")[0]
                    .asJsonObject
                    .getAsJsonObject("sniffing")
            assertEquals(fake, sniffing["enabled"].asBoolean)
            assertEquals(
                if (fake) listOf("fakedns") else emptyList<String>(),
                sniffing.getAsJsonArray("destOverride").map { it.asString },
            )
        }
    }

    @Test
    fun visionRejectsTcpMuxButAllowsXudpAndWireguardRejectsMux() {
        assertThrows(IllegalArgumentException::class.java) {
            compile(KernelSettings(muxEnabled = true))
        }
        val result =
            compile(
                KernelSettings(
                    muxEnabled = true,
                    muxConcurrency = -1,
                    xudpConcurrency = 8,
                    udp443 = Udp443Mode.ALLOW,
                )
            )
        assertEquals(
            -1,
            result
                .getAsJsonArray("outbounds")[0]
                .asJsonObject
                .getAsJsonObject("mux")["concurrency"]
                .asInt,
        )
        val wireguard =
            """{"outbounds":[{"tag":"proxy","protocol":"wireguard","settings":{}}],"inbounds":[{"protocol":"socks"}]}"""
        assertThrows(IllegalArgumentException::class.java) {
            KernelConfigCompiler.compile(
                wireguard,
                KernelSettings(muxEnabled = true, muxConcurrency = -1),
            )
        }
    }

    @Test
    fun socketsAndPolicyApplyToOwnedChainWithoutChangingGeneratedDnsTransport() {
        val settings =
            KernelSettings(
                serverDomainStrategy = ServerDomainStrategy.USE_IPV4V6,
                tcpFastOpen = true,
                tcpKeepAliveInterval = 30,
                tcpUserTimeout = 1000,
                tcpCongestion = TcpCongestion.CUBIC,
                handshake = 8,
                connectionIdle = 20,
                uplinkOnly = 0,
                downlinkOnly = 7,
                bufferSize = 0,
            )
        val root = compile(settings, true)
        root
            .getAsJsonArray("outbounds")
            .map { it.asJsonObject }
            .forEach { outbound ->
                if (
                    outbound["tag"].asString == "proxy" ||
                        outbound["tag"].asString.startsWith("__sx_chain_")
                ) {
                    val socket =
                        outbound.getAsJsonObject("streamSettings").getAsJsonObject("sockopt")
                    assertEquals("UseIPv4v6", socket["domainStrategy"].asString)
                    assertEquals("cubic", socket["tcpCongestion"].asString)
                    assertEquals(1000, socket["tcpUserTimeout"].asInt)
                } else if (outbound["protocol"].asString == "freedom") {
                    val socket =
                        outbound.getAsJsonObject("streamSettings").getAsJsonObject("sockopt")
                    assertEquals("ForceIP", socket["domainStrategy"].asString)
                    if (outbound["tag"].asString == "__sx_dns_transport") {
                        assertEquals(setOf("domainStrategy", "dialerProxy"), socket.keySet())
                        assertEquals("proxy", socket["dialerProxy"].asString)
                    } else assertEquals(setOf("domainStrategy"), socket.keySet())
                } else assertFalse(outbound.has("streamSettings"))
            }
        val levels = root.getAsJsonObject("policy").getAsJsonObject("levels")
        assertEquals(setOf("0", "7"), levels.keySet())
        for (key in listOf("0", "7")) {
            assertEquals(0, levels.getAsJsonObject(key)["bufferSize"].asInt)
            assertEquals(20, levels.getAsJsonObject(key)["connIdle"].asInt)
        }
        assertFalse(compile().has("policy"))
    }

    @Test
    fun bootstrapCopiesAreEndpointScopedRealResolversAndProtectedFromClientRules() {
        val root =
            compile(KernelSettings(serverDomainStrategy = ServerDomainStrategy.USE_IPV4), true)
        val dns = root.getAsJsonObject("dns")
        val resolver =
            dns.getAsJsonArray("servers")
                .first {
                    it.isJsonObject &&
                        it.asJsonObject["address"]?.asString != "fakedns" &&
                        it.asJsonObject.has("domains")
                }
                .asJsonObject
        assertEquals(
            setOf("full:server.test", "full:hop.test"),
            resolver.getAsJsonArray("domains").map { it.asString }.toSet(),
        )
        assertEquals("8.8.8.8", resolver["address"].asString)
        assertTrue(resolver["skipFallback"].asBoolean)
        assertTrue(resolver["finalQuery"].asBoolean)
        val routed =
            JsonParser.parseString(
                    RoutingCompiler.compile(
                        root.toString(),
                        RoutingSettings(defaultRoute = RouteTarget.BLOCK),
                    )
                )
                .asJsonObject
        val rules =
            routed.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        val bootstrap =
            rules.indexOfFirst {
                it.getAsJsonArray("inboundTag")?.any { tag ->
                    tag.asString == resolver["tag"].asString
                } == true
            }
        val block = rules.indexOfFirst { it["outboundTag"]?.asString == "__sx_block" }
        assertTrue(bootstrap in 0 until block)
    }

    @Test
    fun fakeOnlySnifferUsesMetadataWithoutPayloadInspection() {
        for (settings in
            listOf(
                KernelSettings(sniffingEnabled = false),
                KernelSettings(sniffingProtocols = emptySet()),
            )) {
            val sniffing =
                compile(settings, true)
                    .getAsJsonArray("inbounds")[0]
                    .asJsonObject
                    .getAsJsonObject("sniffing")
            assertEquals(true, sniffing["metadataOnly"]?.asBoolean)
        }
        assertEquals(
            false,
            compile(KernelSettings(), true)
                .getAsJsonArray("inbounds")[0]
                .asJsonObject
                .getAsJsonObject("sniffing")["metadataOnly"]
                ?.asBoolean ?: false,
        )
    }

    private fun streamProfile() =
        """{"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"server.test","port":1080}]},"streamSettings":{"address":"override.test.","network":"xhttp","xhttpSettings":{"extra":{"downloadSettings":{"address":"download.test","network":"xhttp","security":"tls","tlsSettings":{"serverName":"cover.test"},"sockopt":{"dialerProxy":"hop","tcpCongestion":"bbr","tcpKeepAliveInterval":77,"mark":12}}}}}},{"tag":"hop","protocol":"socks","settings":{"servers":[{"address":"hop.test","port":1080}]}}]}"""

    private fun streamCompiled(settings: KernelSettings): JsonObject =
        JsonParser.parseString(
                KernelConfigCompiler.compile(
                    DnsConfigCompiler.compile(
                        OwnedConfig.build(streamProfile(), "127.0.0.1", 1080),
                        DnsSettings(),
                        false,
                    ),
                    settings,
                )
            )
            .asJsonObject

    @Test
    fun onlyDialedStreamEndpointsJoinExactBootstrapDomains() {
        val root =
            streamCompiled(KernelSettings(serverDomainStrategy = ServerDomainStrategy.USE_IPV4))
        val domains =
            root
                .getAsJsonObject("dns")
                .getAsJsonArray("servers")[0]
                .asJsonObject
                .getAsJsonArray("domains")
                .map { it.asString }
                .toSet()
        assertEquals(setOf("full:server.test", "full:hop.test", "full:download.test"), domains)
        for ((redirect, expected) in
            listOf(
                "redirect.test:443" to setOf("full:redirect.test"),
                ":443" to emptySet<String>(),
                "127.0.0.1:443" to emptySet<String>(),
                "[::1]:443" to emptySet<String>(),
            )) {
            val profile =
                """{"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"server.test","port":1080}]},"streamSettings":{"sockopt":{"dialerProxy":"redirect"}}},{"tag":"redirect","protocol":"freedom","settings":{"redirect":"$redirect"}}]}"""
            val result =
                JsonParser.parseString(
                        KernelConfigCompiler.compile(
                            DnsConfigCompiler.compile(
                                OwnedConfig.build(profile, "127.0.0.1", 1080),
                                DnsSettings(),
                                false,
                            ),
                            KernelSettings(serverDomainStrategy = ServerDomainStrategy.USE_IPV4),
                        )
                    )
                    .asJsonObject
            val bootstrapDomains =
                result
                    .getAsJsonObject("dns")
                    .getAsJsonArray("servers")[0]
                    .asJsonObject
                    .getAsJsonArray("domains")
                    .map { it.asString }
                    .toSet()
            assertEquals(setOf("full:server.test") + expected, bootstrapDomains)
        }
    }

    @Test
    fun nestedDownloadSocketSettingsFollowControlsAndPreserveTransportAndDependency() {
        for (strategy in listOf(ServerDomainStrategy.AS_IS, ServerDomainStrategy.USE_IPV4)) {
            val root = streamCompiled(KernelSettings(serverDomainStrategy = strategy))
            val download =
                root
                    .getAsJsonArray("outbounds")[0]
                    .asJsonObject
                    .getAsJsonObject("streamSettings")
                    .getAsJsonObject("xhttpSettings")
                    .getAsJsonObject("extra")
                    .getAsJsonObject("downloadSettings")
            val socket = download.getAsJsonObject("sockopt")
            assertEquals(strategy.configValue, socket["domainStrategy"]?.asString)
            assertFalse(socket.has("tcpCongestion"))
            assertFalse(socket.has("tcpKeepAliveInterval"))
            assertEquals("__sx_chain_1", socket["dialerProxy"].asString)
            assertEquals(12, socket["mark"].asInt)
            assertEquals(
                "cover.test",
                download.getAsJsonObject("tlsSettings")["serverName"].asString,
            )
        }
    }

    @Test
    fun unsignedUserLevelsReceiveTheSamePolicyWithoutSignedOverflow() {
        val profile = source.replace("\"level\":7", "\"level\":4294967295")
        val root =
            JsonParser.parseString(
                    KernelConfigCompiler.compile(
                        OwnedConfig.build(profile, "127.0.0.1", 1080),
                        KernelSettings(handshake = 8),
                    )
                )
                .asJsonObject
        assertEquals(
            setOf("0", "4294967295"),
            root.getAsJsonObject("policy").getAsJsonObject("levels").keySet(),
        )
    }

    @Test
    fun optionalNullStreamsAndSocketsUseClientDefaults() {
        for (stream in
            listOf(
                "null",
                "{\"sockopt\":null}",
                "{\"network\":\"xhttp\",\"xhttpSettings\":{\"downloadSettings\":null,\"extra\":null}}",
            )) {
            val profile =
                """{"protocol":"socks","settings":{"servers":[{"address":"server.test","port":1080}],"vnext":null,"peers":null},"streamSettings":$stream}"""
            val root =
                JsonParser.parseString(
                        KernelConfigCompiler.compile(
                            DnsConfigCompiler.compile(
                                OwnedConfig.build(profile, "127.0.0.1", 1080),
                                DnsSettings(),
                                false,
                            ),
                            KernelSettings(serverDomainStrategy = ServerDomainStrategy.USE_IPV4),
                        )
                    )
                    .asJsonObject
            assertEquals(
                "UseIPv4",
                root
                    .getAsJsonArray("outbounds")[0]
                    .asJsonObject
                    .getAsJsonObject("streamSettings")
                    .getAsJsonObject("sockopt")["domainStrategy"]
                    .asString,
            )
            assertEquals(
                listOf("full:server.test"),
                root
                    .getAsJsonObject("dns")
                    .getAsJsonArray("servers")[0]
                    .asJsonObject
                    .getAsJsonArray("domains")
                    .map { it.asString },
            )
        }
    }

    @Test
    fun optionalNullArraysDoNotBreakServerDomainBootstrap() {
        val profile =
            """{"protocol":"socks","settings":{"servers":[{"address":"server.test","port":1080}],"vnext":null,"peers":null},"streamSettings":{"address":null,"sockopt":{},"xhttpSettings":{"downloadSettings":null,"extra":null}}}"""
        val root =
            JsonParser.parseString(
                    KernelConfigCompiler.compile(
                        DnsConfigCompiler.compile(
                            OwnedConfig.build(profile, "127.0.0.1", 1080),
                            DnsSettings(),
                            false,
                        ),
                        KernelSettings(serverDomainStrategy = ServerDomainStrategy.USE_IPV4),
                    )
                )
                .asJsonObject
        assertEquals(
            listOf("full:server.test"),
            root
                .getAsJsonObject("dns")
                .getAsJsonArray("servers")[0]
                .asJsonObject
                .getAsJsonArray("domains")
                .map { it.asString },
        )
    }

    @Test
    fun optionalNullFlowAllowsMuxAndRemainsUnchanged() {
        val profile = source.replace("\"flow\":\"xtls-rprx-vision\"", "\"flow\":null")
        val root =
            JsonParser.parseString(
                    KernelConfigCompiler.compile(
                        OwnedConfig.build(profile, "127.0.0.1", 1080),
                        KernelSettings(muxEnabled = true),
                    )
                )
                .asJsonObject
        val proxy = root.getAsJsonArray("outbounds")[0].asJsonObject
        assertTrue(proxy.getAsJsonObject("mux")["enabled"].asBoolean)
        assertTrue(
            proxy
                .getAsJsonObject("settings")
                .getAsJsonArray("vnext")[0]
                .asJsonObject
                .getAsJsonArray("users")[0]
                .asJsonObject["flow"]
                .isJsonNull
        )
    }

    @Test
    fun optionalNullUserLevelUsesDefaultPolicy() {
        val profile = source.replace("\"level\":7", "\"level\":null")
        val root =
            JsonParser.parseString(
                    KernelConfigCompiler.compile(
                        OwnedConfig.build(profile, "127.0.0.1", 1080),
                        KernelSettings(handshake = 8),
                    )
                )
                .asJsonObject
        val levels = root.getAsJsonObject("policy").getAsJsonObject("levels")
        assertEquals(setOf("0"), levels.keySet())
        assertEquals(8, levels.getAsJsonObject("0")["handshake"].asInt)
    }

    @Test
    fun flatServerAddressesUseDirectBootstrapForEverySupportedServerProtocol() {
        for (protocol in listOf("vmess", "vless", "trojan", "shadowsocks", "socks", "http")) {
            val profile =
                """{"protocol":"$protocol","settings":{"address":"flat.test","port":443,"id":"11111111-1111-1111-1111-111111111111","encryption":"none","password":"secret","method":"aes-128-gcm"}}"""
            val root =
                JsonParser.parseString(
                        KernelConfigCompiler.compile(
                            DnsConfigCompiler.compile(
                                OwnedConfig.build(profile, "127.0.0.1", 1080),
                                DnsSettings(fakeIpEnabled = false),
                                false,
                            ),
                            KernelSettings(serverDomainStrategy = ServerDomainStrategy.USE_IPV4),
                        )
                    )
                    .asJsonObject
            val resolver = root.getAsJsonObject("dns").getAsJsonArray("servers")[0].asJsonObject
            assertEquals(
                protocol,
                listOf("full:flat.test"),
                resolver.getAsJsonArray("domains")?.map { it.asString },
            )
            assertEquals("__sx_server_dns", resolver["tag"].asString)
            assertEquals(
                "flat.test",
                root
                    .getAsJsonArray("outbounds")[0]
                    .asJsonObject
                    .getAsJsonObject("settings")["address"]
                    .asString,
            )
        }
    }

    private fun compileProfile(profile: String, settings: KernelSettings): JsonObject =
        JsonParser.parseString(
                KernelConfigCompiler.compile(
                    DnsConfigCompiler.compile(
                        OwnedConfig.build(profile, "127.0.0.1", 1080),
                        DnsSettings(fakeIpEnabled = false, route = RouteTarget.PROXY),
                        false,
                    ),
                    settings,
                )
            )
            .asJsonObject

    private fun bootstrapDomains(root: JsonObject): Set<String> =
        root
            .getAsJsonObject("dns")
            .getAsJsonArray("servers")
            .filter { it.isJsonObject && it.asJsonObject.has("domains") }
            .flatMap { it.asJsonObject.getAsJsonArray("domains").map { domain -> domain.asString } }
            .toSet()

    @Test
    fun flatAddressesDoNotGrantIgnoredLegacyEndpointsDirectDns() {
        for (protocol in listOf("vmess", "vless", "trojan", "shadowsocks", "socks", "http")) {
            val key = if (protocol in listOf("vmess", "vless")) "vnext" else "servers"
            val profile =
                """{"protocol":"$protocol","settings":{"address":"active.test","port":443,"id":"11111111-1111-1111-1111-111111111111","encryption":"none","password":"secret","method":"aes-128-gcm","$key":[{"address":"ignored.test","port":443}]}}"""
            val root =
                compileProfile(
                    profile,
                    KernelSettings(serverDomainStrategy = ServerDomainStrategy.USE_IPV4),
                )
            assertEquals(protocol, setOf("full:active.test"), bootstrapDomains(root))
            assertEquals(
                JsonParser.parseString(profile).asJsonObject["settings"],
                root.getAsJsonArray("outbounds")[0].asJsonObject["settings"],
            )
        }
    }

    @Test
    fun onlyActiveXhttpDownloadBranchGetsDirectDns() {
        val active = """{"address":"active.test","network":"xhttp"}"""
        val ignored =
            """{"address":"ignored.test","network":"xhttp","sockopt":{"tcpCongestion":"bbr"}}"""
        val cases =
            listOf(
                """{"network":"xhttp","address":"unused-primary.test","xhttpSettings":{"downloadSettings":$active},"splithttpSettings":{"downloadSettings":$ignored}}""" to
                    setOf("full:active.test"),
                """{"network":"splithttp","splithttpSettings":{"downloadSettings":$active}}""" to
                    setOf("full:active.test"),
                """{"network":"xhttp","xhttpSettings":{"downloadSettings":$ignored,"extra":{"downloadSettings":$active}}}""" to
                    setOf("full:active.test"),
                """{"network":"xhttp","xhttpSettings":{"downloadSettings":$ignored,"extra":{}}}""" to
                    emptySet<String>(),
                """{"network":"xhttp","xhttpSettings":{"downloadSettings":$ignored,"extra":null}}""" to
                    emptySet<String>(),
                """{"network":"tcp","xhttpSettings":{"downloadSettings":$ignored}}""" to
                    emptySet<String>(),
                """{"network":"ws","xhttpSettings":{"downloadSettings":$ignored}}""" to
                    emptySet<String>(),
                """{"method":"raw","network":"xhttp","xhttpSettings":{"downloadSettings":$ignored}}""" to
                    emptySet<String>(),
                """{"method":"xhttp","network":"raw","xhttpSettings":{"downloadSettings":$active}}""" to
                    setOf("full:active.test"),
            )
        for ((stream, expected) in cases) {
            val profile =
                """{"protocol":"socks","settings":{"servers":[{"address":"server.test","port":1080}]},"streamSettings":$stream}"""
            val root =
                compileProfile(
                    profile,
                    KernelSettings(serverDomainStrategy = ServerDomainStrategy.USE_IPV4),
                )
            assertEquals(stream, setOf("full:server.test") + expected, bootstrapDomains(root))
        }
    }

    private fun flatVless(legacyUser: String) =
        """{"protocol":"vless","settings":{"address":"active.test","port":443,"id":"11111111-1111-1111-1111-111111111111","encryption":"none","flow":"","level":7,"vnext":[{"address":"ignored.test","port":443,"users":[$legacyUser]}]}}"""

    @Test
    fun flatVlessMuxIgnoresDiscardedLegacyVisionUser() {
        val profile = flatVless("""{"flow":"xtls-rprx-vision","level":-1}""")
        val root = compileProfile(profile, KernelSettings(muxEnabled = true, handshake = 8))
        assertTrue(
            root
                .getAsJsonArray("outbounds")[0]
                .asJsonObject
                .getAsJsonObject("mux")["enabled"]
                .asBoolean
        )
        assertEquals(
            setOf("0", "7"),
            root.getAsJsonObject("policy").getAsJsonObject("levels").keySet(),
        )
        assertEquals(
            JsonParser.parseString(profile).asJsonObject["settings"],
            root.getAsJsonArray("outbounds")[0].asJsonObject["settings"],
        )
        val vision = profile.replace("\"flow\":\"\"", "\"flow\":\"xtls-rprx-vision\"")
        assertThrows(IllegalArgumentException::class.java) {
            compileProfile(vision, KernelSettings(muxEnabled = true))
        }
    }

    @Test
    fun flatPolicyIgnoresDiscardedRawUserLevels() {
        val profile = flatVless("""{"level":-1,"metadata":{"level":-99}}""")
        val root = compileProfile(profile, KernelSettings(handshake = 8))
        assertEquals(
            setOf("0", "7"),
            root.getAsJsonObject("policy").getAsJsonObject("levels").keySet(),
        )
    }

    @Test
    fun vmessMetadataFlowIsNotVlessVisionAndMetadataLevelsAreIgnored() {
        val profile =
            """{"protocol":"vmess","settings":{"vnext":[{"address":"server.test","port":443,"users":[{"id":"11111111-1111-1111-1111-111111111111","security":"auto","level":5,"flow":"xtls-rprx-vision","metadata":{"level":-1}}]}],"metadata":{"level":-2,"flow":"xtls-rprx-vision"}}}"""
        val root = compileProfile(profile, KernelSettings(muxEnabled = true, handshake = 8))
        assertEquals(
            setOf("0", "5"),
            root.getAsJsonObject("policy").getAsJsonObject("levels").keySet(),
        )
    }

    @Test
    fun policyReadsOnlyProtocolAccountsThatCoreUses() {
        for (protocol in listOf("socks", "http")) {
            for (user in listOf("", "user")) {
                val profile =
                    """{"protocol":"$protocol","settings":{"address":"server.test","port":1080,"user":"$user","level":7}}"""
                val root = compileProfile(profile, KernelSettings(handshake = 8))
                assertEquals(
                    if (user.isEmpty()) setOf("0") else setOf("0", "7"),
                    root.getAsJsonObject("policy").getAsJsonObject("levels").keySet(),
                )
            }
            val profile =
                """{"protocol":"$protocol","settings":{"servers":[{"address":"server.test","port":1080,"level":-1,"users":[{"user":"user","level":6,"metadata":{"level":-2}}]}]}}"""
            val root = compileProfile(profile, KernelSettings(handshake = 8))
            assertEquals(
                setOf("0", "6"),
                root.getAsJsonObject("policy").getAsJsonObject("levels").keySet(),
            )
        }
        for (protocol in listOf("trojan", "shadowsocks")) {
            val profile =
                """{"protocol":"$protocol","settings":{"servers":[{"address":"server.test","port":443,"level":5,"password":"secret","method":"aes-128-gcm","metadata":{"level":-1}}]}}"""
            val root = compileProfile(profile, KernelSettings(handshake = 8))
            assertEquals(
                setOf("0", "5"),
                root.getAsJsonObject("policy").getAsJsonObject("levels").keySet(),
            )
        }
    }

    @Test
    fun bootstrapIgnoresChainsReferencedOnlyByInactiveTransportBranches() {
        val profile =
            """{"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"address":"server.test","port":1080},"streamSettings":{"network":"xhttp","xhttpSettings":{},"splithttpSettings":{"downloadSettings":{"address":"unused-download.test","network":"xhttp","sockopt":{"dialerProxy":"unused"}}}}},{"tag":"unused","protocol":"socks","settings":{"address":"unused-hop.test","port":1080}}]}"""
        val root =
            compileProfile(
                profile,
                KernelSettings(serverDomainStrategy = ServerDomainStrategy.USE_IPV4),
            )
        assertEquals(setOf("full:server.test"), bootstrapDomains(root))
        val outbounds = root.getAsJsonArray("outbounds").map { it.asJsonObject }
        assertEquals(
            "unused-hop.test",
            outbounds
                .first { it["tag"]?.asString == "__sx_chain_1" }
                .getAsJsonObject("settings")["address"]
                .asString,
        )
        assertEquals(
            "__sx_chain_1",
            outbounds[0]
                .getAsJsonObject("streamSettings")
                .getAsJsonObject("splithttpSettings")
                .getAsJsonObject("downloadSettings")
                .getAsJsonObject("sockopt")["dialerProxy"]
                .asString,
        )
        for (transportLayer in listOf(false, true)) {
            val chained =
                """{"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"address":"server.test","port":1080},"proxySettings":{"tag":"active","transportLayer":$transportLayer},"streamSettings":{"network":"xhttp","sockopt":{"dialerProxy":"ignored"},"xhttpSettings":{}}},{"tag":"active","protocol":"socks","settings":{"address":"active-hop.test","port":1080}},{"tag":"ignored","protocol":"socks","settings":{"address":"ignored-hop.test","port":1080}}]}"""
            assertEquals(
                setOf("full:server.test", "full:active-hop.test"),
                bootstrapDomains(
                    compileProfile(
                        chained,
                        KernelSettings(serverDomainStrategy = ServerDomainStrategy.USE_IPV4),
                    )
                ),
            )
        }
    }
}
