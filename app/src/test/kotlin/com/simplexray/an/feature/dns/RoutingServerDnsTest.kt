package com.simplexray.an.feature.dns

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.core.config.dns.DnsConfigCompiler
import com.simplexray.an.core.config.routing.RoutingServerTags
import com.simplexray.an.feature.dns.model.DnsSettings
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingBlock
import com.simplexray.an.feature.routing.model.RoutingBlocks
import com.simplexray.an.feature.routing.model.RoutingSettings
import org.junit.Assert.*
import org.junit.Test

class RoutingServerDnsTest {
    @Test
    fun customServerDnsOverridesGlobalDirectAndKeepsFallbackOnSameExit() {
        val result = compile(listOf(server("alpha", "first")), fallback = true)
        val servers = servers(result)
        assertEquals(4, servers.size)
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), servers.take(2).map { it["address"].asString })
        for (server in servers.take(2)) {
            assertEquals(
                listOf("domain:first.test"),
                server.getAsJsonArray("domains").map { it.asString },
            )
            assertTrue(server["skipFallback"].asBoolean)
            assertEquals(
                RoutingServerTags.outbound("alpha"),
                transport(result, server)
                    .getAsJsonObject("streamSettings")
                    .getAsJsonObject("sockopt")["dialerProxy"]
                    .asString,
            )
        }
        assertFalse(servers[0].has("finalQuery"))
        assertTrue(servers[1]["finalQuery"].asBoolean)
        assertFalse(
            transport(result, servers.last())
                .getAsJsonObject("streamSettings")
                .getAsJsonObject("sockopt")
                .has("dialerProxy")
        )
        assertEquals(2, servers.take(2).map { it["tag"].asString }.distinct().size)
    }

    @Test
    fun orderedGroupsKeepDifferentServersMainAndDirectBarriers() {
        val result =
            compile(
                listOf(
                    server("alpha", "first"),
                    server("beta", "second"),
                    RoutingBlock(RouteTarget.PROXY, "suffix:main.test"),
                    server("gamma", "third"),
                    RoutingBlock(RouteTarget.DIRECT, "suffix:direct.test"),
                    server("delta", "fourth"),
                    RoutingBlock(RouteTarget.BLOCK, "suffix:block.test"),
                    server("epsilon", "fifth"),
                )
            )
        val groups = servers(result).filter { it.has("domains") }
        assertEquals(
            listOf("first", "second", "main", "third", "direct", "fourth", "block", "fifth").map {
                "domain:$it.test"
            },
            groups.map { it.getAsJsonArray("domains").single().asString },
        )
        assertEquals(
            listOf("alpha", "beta", null, "gamma", null, "delta", null, "epsilon").map {
                it?.let(RoutingServerTags::outbound)
            },
            groups.map {
                transport(result, it)
                    .getAsJsonObject("streamSettings")
                    .getAsJsonObject("sockopt")
                    .get("dialerProxy")
                    ?.asString
            },
        )
        assertTrue(groups.all { it["skipFallback"].asBoolean && it["finalQuery"].asBoolean })
        assertEquals(groups.size, groups.map { it["tag"].asString }.distinct().size)
    }

    @Test
    fun adjacentRulesCoalesceOnlyForSameServer() {
        val result =
            compile(
                listOf(
                    RoutingBlock(RouteTarget.PROXY, "suffix:first.test\nfull:second.test", "alpha"),
                    server("beta", "third"),
                )
            )
        val groups = servers(result).filter { it.has("domains") }
        assertEquals(2, groups.size)
        assertEquals(
            listOf("domain:first.test", "full:second.test"),
            groups[0].getAsJsonArray("domains").map { it.asString },
        )
        assertEquals(
            listOf("domain:third.test"),
            groups[1].getAsJsonArray("domains").map { it.asString },
        )
    }

    @Test
    fun emptyCustomBlocksDoNotEnableSplitDns() {
        val result =
            compile(listOf(RoutingBlock(RouteTarget.PROXY, "# suffix:first.test", "alpha")))
        assertEquals(1, servers(result).size)
        assertFalse(servers(result).single().has("domains"))
        assertFalse(
            transport(result, servers(result).single())
                .getAsJsonObject("streamSettings")
                .getAsJsonObject("sockopt")
                .has("dialerProxy")
        )
    }

    @Test
    fun missingCustomOutboundFailsInsteadOfLeakingToDefaultDns() {
        assertThrows(IllegalArgumentException::class.java) {
            DnsConfigCompiler.compile(
                source(emptyList()),
                DnsSettings(route = RouteTarget.DIRECT),
                false,
                routing(listOf(server("alpha", "first"))),
            )
        }
    }

    private fun server(id: String, domain: String) =
        RoutingBlock(RouteTarget.PROXY, "suffix:$domain.test", id)

    private fun routing(blocks: List<RoutingBlock>): RoutingSettings {
        val all =
            blocks +
                RouteTarget.entries
                    .filter { target -> blocks.none { !it.isServer && it.target == target } }
                    .map { RoutingBlock(it) }
        return RoutingSettings(
            bypassLan = false,
            blocks = all,
            rules = all.flatMap(RoutingBlocks::parse),
        )
    }

    private fun source(blocks: List<RoutingBlock>): String =
        JsonObject()
            .apply {
                add(
                    "inbounds",
                    JsonParser.parseString(
                        """[{"tag":"client","protocol":"socks","port":10808,"settings":{"udp":true}}]"""
                    ),
                )
                add(
                    "outbounds",
                    JsonArray().apply {
                        add(
                            JsonParser.parseString(
                                """{"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":19001}]}}"""
                            )
                        )
                        blocks
                            .filter { it.isServer }
                            .forEach { block ->
                                add(
                                    JsonParser.parseString(
                                        """{"tag":"${RoutingServerTags.outbound(block.id)}","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":19002}]}}"""
                                    )
                                )
                            }
                    },
                )
            }
            .toString()

    private fun compile(blocks: List<RoutingBlock>, fallback: Boolean = false): JsonObject =
        JsonParser.parseString(
                DnsConfigCompiler.compile(
                    source(blocks),
                    DnsSettings(
                        fakeIpEnabled = false,
                        route = RouteTarget.DIRECT,
                        fallbackEnabled = fallback,
                    ),
                    false,
                    routing(blocks),
                )
            )
            .asJsonObject

    private fun servers(root: JsonObject) =
        root.getAsJsonObject("dns").getAsJsonArray("servers").map { it.asJsonObject }

    private fun transport(root: JsonObject, server: JsonObject): JsonObject {
        val route =
            root
                .getAsJsonObject("routing")
                .getAsJsonArray("rules")
                .map { it.asJsonObject }
                .single {
                    it.getAsJsonArray("inboundTag")?.any { tag ->
                        tag.asString == server["tag"].asString
                    } == true
                }
        return root
            .getAsJsonArray("outbounds")
            .map { it.asJsonObject }
            .single { it["tag"].asString == route["outboundTag"].asString }
            .also {
                assertEquals("freedom", it["protocol"].asString)
                assertEquals(
                    "ForceIP",
                    it.getAsJsonObject("streamSettings")
                        .getAsJsonObject("sockopt")["domainStrategy"]
                        .asString,
                )
            }
    }
}
