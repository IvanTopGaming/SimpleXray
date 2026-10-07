package com.simplexray.an.feature.dns

import com.google.gson.JsonParser
import com.simplexray.an.core.config.dns.DnsConfigCompiler
import com.simplexray.an.core.config.ownership.OwnedConfig
import com.simplexray.an.core.config.routing.RoutingCompiler
import com.simplexray.an.feature.dns.model.DnsSettings
import com.simplexray.an.feature.routing.model.DomainStrategy
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingRule
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.routing.model.RuleKind
import org.junit.Assert.*
import org.junit.Test

class DnsCompilerTest {
    private val source =
        OwnedConfig.build(
            """{"protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":12345}]}}""",
            "127.0.0.1",
            10808,
        )

    @Test
    fun fakeDnsPoolsAndInterceptionPrecedeLanAndUserRoutes() {
        val result =
            JsonParser.parseString(
                    RoutingCompiler.compile(
                        DnsConfigCompiler.compile(source, DnsSettings(), true),
                        RoutingSettings(defaultRoute = RouteTarget.BLOCK),
                    )
                )
                .asJsonObject
        assertEquals(
            DnsConfigCompiler.fakePools,
            result.getAsJsonArray("fakedns").map { it.asJsonObject["ipPool"].asString },
        )
        assertTrue(
            result.getAsJsonArray("fakedns").all { it.asJsonObject["poolSize"].asInt == 32768 }
        )
        val rules = result.getAsJsonObject("routing").getAsJsonArray("rules")
        assertEquals("53", rules[0].asJsonObject["port"].asString)
        assertEquals("tcp,udp", rules[0].asJsonObject["network"].asString)
        assertEquals(DnsConfigCompiler.GUARD_RULE, rules[2].asJsonObject["ruleTag"].asString)
        assertEquals(
            DnsConfigCompiler.fakePools,
            rules[2].asJsonObject.getAsJsonArray("ip").map { it.asString },
        )
        assertTrue(
            result
                .getAsJsonArray("inbounds")[0]
                .asJsonObject
                .getAsJsonObject("sniffing")
                .getAsJsonArray("destOverride")
                .any { it.asString == "fakedns" }
        )
    }

    @Test
    fun disabledFakeDnsUsesRealResolversAndKeepsDomainSniffing() {
        val result =
            JsonParser.parseString(
                    DnsConfigCompiler.compile(
                        source,
                        DnsSettings(
                            fakeIpEnabled = false,
                            fallbackEnabled = true,
                            cacheEnabled = false,
                        ),
                        false,
                    )
                )
                .asJsonObject
        assertFalse(result.has("fakedns"))
        val dns = result.getAsJsonObject("dns")
        assertTrue(dns["disableCache"].asBoolean)
        assertEquals("UseIPv4", dns["queryStrategy"].asString)
        assertEquals(
            listOf("8.8.8.8", "1.1.1.1"),
            dns.getAsJsonArray("servers").map { it.asJsonObject["address"].asString },
        )
        assertEquals(
            listOf("http", "tls", "quic"),
            result
                .getAsJsonArray("inbounds")[0]
                .asJsonObject
                .getAsJsonObject("sniffing")
                .getAsJsonArray("destOverride")
                .map { it.asString },
        )
    }

    @Test
    fun dohBootstrapAndServerOutboundDependenciesArePreserved() {
        val input =
            OwnedConfig.build(
                """{"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"server.example","port":443}]},"proxySettings":{"tag":"hop"}},{"tag":"hop","protocol":"socks","settings":{"servers":[{"address":"hop.example","port":443}]}}]}""",
                "127.0.0.1",
                10808,
            )
        val before = JsonParser.parseString(input).asJsonObject.getAsJsonArray("outbounds")
        val result =
            JsonParser.parseString(
                    DnsConfigCompiler.compile(
                        input,
                        DnsSettings(
                            primaryDns = "https://dns.example/dns-query",
                            primaryBootstrap = "9.9.9.9",
                        ),
                        true,
                    )
                )
                .asJsonObject
        assertEquals(
            "9.9.9.9",
            result.getAsJsonObject("dns").getAsJsonObject("hosts")["dns.example"].asString,
        )
        before.forEachIndexed { index, outbound ->
            assertEquals(outbound, result.getAsJsonArray("outbounds")[index])
        }
        val transport =
            result
                .getAsJsonArray("outbounds")
                .map { it.asJsonObject }
                .single { it["tag"].asString.startsWith("__sx_dns_transport") }
        assertEquals(
            "proxy",
            transport
                .getAsJsonObject("streamSettings")
                .getAsJsonObject("sockopt")["dialerProxy"]
                .asString,
        )
        assertEquals(
            "ForceIP",
            transport
                .getAsJsonObject("streamSettings")
                .getAsJsonObject("sockopt")["domainStrategy"]
                .asString,
        )
    }

    @Test
    fun fakeIpUsesRealIpEvaluationOnlyWhenActiveIpRulesNeedIt() {
        fun strategy(fake: Boolean, settings: RoutingSettings): String {
            val compiled =
                RoutingCompiler.compile(
                    DnsConfigCompiler.compile(source, DnsSettings(fakeIpEnabled = fake), false),
                    settings,
                )
            return JsonParser.parseString(compiled)
                .asJsonObject
                .getAsJsonObject("routing")["domainStrategy"]
                .asString
        }
        val base = RoutingSettings(bypassLan = false, domainStrategy = DomainStrategy.AS_IS)
        val ip = RoutingRule(name = "IP", kind = RuleKind.IP, values = listOf("127.0.0.1"))
        val geoip = RoutingRule(name = "GeoIP", kind = RuleKind.GEOIP, values = listOf("ru"))
        val domain = RoutingRule(name = "Domain", values = listOf("target.test"))
        val geosite = RoutingRule(name = "GeoSite", kind = RuleKind.GEOSITE, values = listOf("ru"))
        for (rule in listOf(ip, geoip)) {
            val settings = base.copy(rules = listOf(rule))
            val snapshot = settings.encode()
            assertEquals("IPOnDemand", strategy(true, settings))
            assertEquals(snapshot, settings.encode())
            assertEquals(DomainStrategy.AS_IS, RoutingSettings.decode(snapshot).domainStrategy)
            assertEquals("AsIs", strategy(false, settings))
            assertEquals(
                "AsIs",
                strategy(true, base.copy(rules = listOf(rule.copy(enabled = false)))),
            )
        }
        assertEquals("IPOnDemand", strategy(true, base.copy(bypassLan = true)))
        assertEquals("AsIs", strategy(false, base.copy(bypassLan = true)))
        assertEquals("AsIs", strategy(true, base.copy(rules = listOf(domain, geosite))))
        assertEquals("AsIs", strategy(true, base))
        assertEquals(
            "IPOnDemand",
            strategy(false, base.copy(domainStrategy = DomainStrategy.IP_ON_DEMAND)),
        )
    }

    @Test
    fun geoRulesValidateCompileAndRequireOnlyActiveAssets() {
        val ip = RoutingRule(name = "IP", kind = RuleKind.GEOIP, values = listOf("RU", "!CN"))
        val site =
            RoutingRule(
                name = "Site",
                kind = RuleKind.GEOSITE,
                values = listOf("category-ads-all", "google@cn", "google@cn@ads"),
            )
        val settings = RoutingSettings(rules = listOf(ip, site))
        assertEquals(setOf("geoip.dat", "geosite.dat"), RoutingCompiler.requiredGeoFiles(settings))
        assertEquals(
            setOf("geoip.dat"),
            RoutingCompiler.requiredGeoFiles(
                settings.copy(rules = listOf(ip, site.copy(enabled = false)))
            ),
        )
        assertEquals(emptySet<String>(), RoutingCompiler.requiredGeoFiles(RoutingSettings()))
        val output = RoutingCompiler.compile(source, settings)
        assertTrue(output.contains("geoip:ru"))
        assertTrue(output.contains("geosite:google@cn"))
        assertEquals(settings, RoutingSettings.decode(settings.encode()))
        for (bad in
            listOf(
                "ext:file:ru",
                "../ru",
                "ru/xx",
                "",
                "ru@",
                "*",
                "ru @cn",
                "ru@!cn",
            )) assertThrows(IllegalArgumentException::class.java) {
            site.copy(values = listOf(bad)).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            ip.copy(values = listOf("ru@cn")).validate()
        }
    }
}
