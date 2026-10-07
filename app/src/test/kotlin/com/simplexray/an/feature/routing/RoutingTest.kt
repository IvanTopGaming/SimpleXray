package com.simplexray.an.feature.routing

import com.google.gson.JsonParser
import com.simplexray.an.core.config.routing.RoutingCompiler
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingRule
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.routing.model.RuleKind
import org.junit.Assert.*
import org.junit.Test

class RoutingTest {
    private val source =
        """{"inbounds":[{"protocol":"socks","tag":"client","port":10808}],"outbounds":[{"protocol":"freedom","tag":"direct"},{"protocol":"vless","tag":"proxy"},{"protocol":"dns","tag":"dns-out"}],"dns":{"tag":"dns-internal","servers":["localhost"]},"routing":{"domainStrategy":"IPIfNonMatch","rules":[{"port":53,"outboundTag":"dns-out"},{"inboundTag":["dns-internal"],"outboundTag":"direct"}]}}"""

    private fun rule(
        id: String = "a",
        kind: RuleKind = RuleKind.DOMAIN,
        values: List<String> = listOf("example.com"),
        target: RouteTarget = RouteTarget.BLOCK,
    ) = RoutingRule(id, "Example", kind, values, target)

    @Test
    fun legacyDisabledModeCannotBypassClientRules() {
        val result = RoutingCompiler.compile(source, RoutingSettings(enabled = false))
        assertNotEquals(source, result)
        assertTrue(result.contains("__sx_direct"))
    }

    @Test
    fun snapshotsRoundTripAndRejectCorruption() {
        val settings = RoutingSettings(enabled = true, rules = listOf(rule()))
        assertEquals(settings, RoutingSettings.decode(settings.encode()))
        assertEquals(RoutingSettings(), RoutingSettings.decode(null))
        for (bad in
            listOf(
                "",
                "null",
                "{}",
                "{\"version\":99}",
                settings.encode().replace("BLOCK", "SURPRISE"),
            )) {
            assertThrows(IllegalArgumentException::class.java) { RoutingSettings.decode(bad) }
        }
    }

    @Test
    fun mutationsPreserveIdentityAndPriority() {
        val a = rule()
        val b = rule("b")
        val initial = RoutingSettings(rules = listOf(a, b))
        val moved = initial.move("b", -1).upsert(a.copy(enabled = false))
        assertEquals(listOf("b", "a"), moved.rules.map { it.id })
        assertFalse(moved.rules.last().enabled)
        assertEquals(listOf(a, b), initial.rules)
        assertEquals(listOf("b"), moved.remove("a").rules.map { it.id })
        assertEquals(initial, initial.move("a", -1))
    }

    @Test
    fun inputValidationRejectsAmbiguousOrUnboundedRules() {
        val invalid =
            listOf(
                "https://example.com/path",
                "*.example.com",
                "example.com:443",
                "-bad.com",
                "example..com",
                "127.0.0.1",
                "",
                "a b",
            )
        invalid.forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) {
                rule(values = listOf(value)).validate()
            }
        }
        listOf(
                "1.2.3.999",
                "1.2.3.4/33",
                "example.com",
                "::1/129",
                "fe80::1%wlan0",
                "1.2.3",
                "01.2.3.4",
                "1.2.3.4/-1",
            )
            .forEach {
                assertThrows(it, IllegalArgumentException::class.java) {
                    rule(kind = RuleKind.IP, values = listOf(it)).validate()
                }
            }
        listOf("0.0.0.0/0", "192.168.1.1", "::/0", "2001:db8::/32", "::1").forEach {
            rule(kind = RuleKind.IP, values = listOf(it)).validate()
        }
        rule(values = listOf("пример.рф", "Example.COM")).validate()
        assertThrows(IllegalArgumentException::class.java) {
            RoutingSettings(rules = listOf(rule(), rule())).validate()
        }
        assertThrows(IllegalArgumentException::class.java) { rule(values = emptyList()).validate() }
        assertThrows(IllegalArgumentException::class.java) {
            rule(values = List(129) { "example.com" }).validate()
        }
    }

    @Test
    fun customRulesAreOrderedScopedAndPreserveDnsBootstrap() {
        val settings =
            RoutingSettings(
                enabled = true,
                bypassLan = true,
                rules =
                    listOf(
                        rule(),
                        rule("off").copy(enabled = false),
                        rule("ip", RuleKind.IP, listOf("203.0.113.0/24"), RouteTarget.DIRECT),
                    ),
            )
        val compiled =
            JsonParser.parseString(RoutingCompiler.compile(source, settings)).asJsonObject
        val rules = compiled.getAsJsonObject("routing").getAsJsonArray("rules")
        assertEquals("dns-out", rules[0].asJsonObject["outboundTag"].asString)
        assertTrue(rules[1].asJsonObject.has("ip"))
        assertEquals(
            "domain:example.com",
            rules[2].asJsonObject.getAsJsonArray("domain")[0].asString,
        )
        assertEquals("203.0.113.0/24", rules[3].asJsonObject.getAsJsonArray("ip")[0].asString)
        for (i in 1..4) assertEquals(
            "client",
            rules[i].asJsonObject.getAsJsonArray("inboundTag")[0].asString,
        )
        assertEquals("proxy", rules[4].asJsonObject["outboundTag"].asString)
        assertEquals("dns-internal", rules[5].asJsonObject.getAsJsonArray("inboundTag")[0].asString)
        assertEquals(JsonParser.parseString(source).asJsonObject["dns"], compiled["dns"])
        assertEquals(
            3,
            JsonParser.parseString(source).asJsonObject.getAsJsonArray("outbounds").size(),
        )
    }

    @Test
    fun supportsExactDomainsAndUniqueTagsWithoutOverwritingSniffing() {
        val json = JsonParser.parseString(source).asJsonObject
        val inbound = json.getAsJsonArray("inbounds")[0].asJsonObject
        inbound.remove("tag")
        inbound.add(
            "sniffing",
            JsonParser.parseString("""{"enabled":true,"destOverride":["fakedns"]}"""),
        )
        json.getAsJsonArray("outbounds")[0].asJsonObject.addProperty("tag", "__sx_direct")
        val result =
            JsonParser.parseString(
                    RoutingCompiler.compile(
                        json.toString(),
                        RoutingSettings(
                            enabled = true,
                            rules = listOf(rule(kind = RuleKind.FULL_DOMAIN)),
                        ),
                    )
                )
                .asJsonObject
        val outputInbound = result.getAsJsonArray("inbounds")[0].asJsonObject
        assertTrue(outputInbound["tag"].asString.isNotBlank())
        assertEquals(inbound["sniffing"], outputInbound["sniffing"])
        assertEquals(
            1,
            result.getAsJsonArray("outbounds").count {
                it.asJsonObject["tag"]?.asString == "__sx_direct"
            },
        )
        assertTrue(result.toString().contains("full:example.com"))
    }

    @Test
    fun ambiguousOrMissingProxyFailsInsteadOfSelectingDirect() {
        val json = JsonParser.parseString(source).asJsonObject
        json.getAsJsonArray("outbounds")[1].asJsonObject.addProperty("tag", "first")
        json
            .getAsJsonArray("outbounds")
            .add(JsonParser.parseString("""{"protocol":"trojan","tag":"second"}"""))
        assertThrows(IllegalArgumentException::class.java) {
            RoutingCompiler.compile(json.toString(), RoutingSettings(enabled = true))
        }
        json.getAsJsonArray("outbounds").remove(3)
        json.getAsJsonArray("outbounds").remove(1)
        assertThrows(IllegalArgumentException::class.java) {
            RoutingCompiler.compile(json.toString(), RoutingSettings(enabled = true))
        }
        assertNotNull(
            RoutingCompiler.compile(
                json.toString(),
                RoutingSettings(enabled = true, defaultRoute = RouteTarget.BLOCK),
            )
        )
    }

    @Test
    fun enablingDomainRulesAddsRoutingOnlySniffing() {
        val result =
            JsonParser.parseString(
                    RoutingCompiler.compile(
                        source,
                        RoutingSettings(enabled = true, rules = listOf(rule())),
                    )
                )
                .asJsonObject
        val sniffing = result.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonObject("sniffing")
        assertTrue(sniffing["enabled"].asBoolean)
        assertTrue(sniffing["routeOnly"].asBoolean)
        assertEquals(
            listOf("http", "tls", "quic"),
            sniffing.getAsJsonArray("destOverride").map { it.asString },
        )
    }

    @Test
    fun generatedClientTagsCannotCollideWithInternalDnsTraffic() {
        val json = JsonParser.parseString(source).asJsonObject
        json.getAsJsonArray("inbounds")[0].asJsonObject.remove("tag")
        json.getAsJsonObject("dns").addProperty("tag", "__sx_client")
        json
            .getAsJsonObject("dns")
            .add(
                "servers",
                JsonParser.parseString("""[{"address":"1.1.1.1","tag":"__sx_client_1"}]"""),
            )
        val result =
            JsonParser.parseString(
                    RoutingCompiler.compile(json.toString(), RoutingSettings(enabled = true))
                )
                .asJsonObject
        val client = result.getAsJsonArray("inbounds")[0].asJsonObject["tag"].asString
        assertNotEquals("__sx_client", client)
        assertNotEquals("__sx_client_1", client)
    }

    @Test
    fun existingClientAndInternalDnsCollisionIsRejected() {
        val json = JsonParser.parseString(source).asJsonObject
        json.getAsJsonObject("dns").addProperty("tag", "client")
        assertThrows(IllegalArgumentException::class.java) {
            RoutingCompiler.compile(json.toString(), RoutingSettings(enabled = true))
        }
        json.getAsJsonObject("dns").addProperty("tag", "internal")
        json
            .getAsJsonObject("dns")
            .add("servers", JsonParser.parseString("""[{"address":"1.1.1.1","tag":"client"}]"""))
        assertThrows(IllegalArgumentException::class.java) {
            RoutingCompiler.compile(json.toString(), RoutingSettings(enabled = true))
        }
    }

    @Test
    fun inboundAndOutboundMayShareTagButSameNamespaceMayNot() {
        val json = JsonParser.parseString(source).asJsonObject
        json.getAsJsonArray("inbounds")[0].asJsonObject.addProperty("tag", "proxy")
        assertNotNull(RoutingCompiler.compile(json.toString(), RoutingSettings(enabled = true)))
        json.getAsJsonArray("outbounds").add(json.getAsJsonArray("outbounds")[1].deepCopy())
        assertThrows(IllegalArgumentException::class.java) {
            RoutingCompiler.compile(json.toString(), RoutingSettings(enabled = true))
        }
    }
}
