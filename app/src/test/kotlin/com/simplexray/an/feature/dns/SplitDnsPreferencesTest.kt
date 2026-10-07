package com.simplexray.an.feature.dns

import android.app.Application
import com.google.gson.JsonParser
import com.simplexray.an.core.config.AppConfig
import com.simplexray.an.core.config.profile.ProfileGeodata
import com.simplexray.an.core.config.profile.ProfileOverrides
import com.simplexray.an.feature.dns.model.DnsSettings
import com.simplexray.an.feature.kernel.model.KernelSettings
import com.simplexray.an.feature.kernel.model.ServerDomainStrategy
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingRule
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.routing.model.RuleKind
import com.simplexray.an.prefs.Preferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SplitDnsPreferencesTest {
    private val source =
        """{"protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":12345}]}}"""
    private val routing =
        RoutingSettings(
            bypassLan = false,
            rules =
                listOf(
                    RoutingRule(
                        name = "Direct",
                        values = listOf("example.com"),
                        target = RouteTarget.DIRECT,
                    )
                ),
        )

    @Test
    fun directRulesUseYandexWithAnIndependentDirectTransport() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        prefs.dnsSettingsJson = DnsSettings(fakeIpEnabled = false).encode()
        val root =
            JsonParser.parseString(AppConfig(prefs).buildProfile(source, routing)).asJsonObject
        val direct =
            root
                .getAsJsonObject("dns")
                .getAsJsonArray("servers")
                .map { it.asJsonObject }
                .firstOrNull { it["address"].asString == "77.88.8.8" }
        assertNotNull("Direct rules must have a separate resolver", direct)
        assertEquals("domain:example.com", direct!!.getAsJsonArray("domains")[0].asString)
        val dnsRoute =
            root
                .getAsJsonObject("routing")
                .getAsJsonArray("rules")
                .map { it.asJsonObject }
                .single { rule ->
                    rule.getAsJsonArray("inboundTag")?.any {
                        it.asString == direct["tag"].asString
                    } == true
                }
        val transport =
            root
                .getAsJsonArray("outbounds")
                .map { it.asJsonObject }
                .single { it["tag"] == dnsRoute["outboundTag"] }
        assertEquals("freedom", transport["protocol"].asString)
        assertFalse(
            transport
                .getAsJsonObject("streamSettings")
                .getAsJsonObject("sockopt")
                .has("dialerProxy")
        )
    }

    @Test
    fun splitIgnoresDisabledAndIpRulesAndPreservesGeositeReferences() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        val rules =
            listOf(
                RoutingRule(
                    name = "Off",
                    enabled = false,
                    target = RouteTarget.DIRECT,
                    values = listOf("disabled.test"),
                ),
                RoutingRule(
                    name = "IP",
                    kind = RuleKind.GEOIP,
                    target = RouteTarget.DIRECT,
                    values = listOf("ru"),
                ),
            )
        val base = routing.copy(rules = rules)
        val without =
            JsonParser.parseString(AppConfig(prefs).buildProfile(source, base)).asJsonObject
        assertFalse(without.getAsJsonObject("dns").toString().contains("77.88.8.8"))
        val geo =
            rules +
                RoutingRule(
                    name = "Sites",
                    kind = RuleKind.GEOSITE,
                    target = RouteTarget.DIRECT,
                    values = listOf("ru"),
                )
        val config = AppConfig(prefs).buildProfile(source, base.copy(rules = geo))
        val dns = JsonParser.parseString(config).asJsonObject.getAsJsonObject("dns").toString()
        assertTrue(dns.contains("geosite:ru"))
        assertFalse(dns.contains("geoip:ru"))
        assertFalse(dns.contains("disabled.test"))
        assertEquals(setOf("geoip.dat", "geosite.dat"), ProfileGeodata.requiredFiles(config))
    }

    @Test
    fun serverBootstrapDoesNotDuplicateEverySplitRule() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        prefs.kernelSettingsJson =
            KernelSettings(serverDomainStrategy = ServerDomainStrategy.USE_IPV4).encode()
        val config =
            AppConfig(prefs).buildProfile(source.replace("127.0.0.1", "vpn.example"), routing)
        val servers =
            JsonParser.parseString(config)
                .asJsonObject
                .getAsJsonObject("dns")
                .getAsJsonArray("servers")
        val bootstrap =
            servers.filter {
                it.isJsonObject &&
                    it.asJsonObject["tag"]?.asString?.startsWith("__sx_server_dns") == true
            }
        assertEquals(1, bootstrap.size)
        assertEquals("8.8.8.8", bootstrap.single().asJsonObject["address"].asString)
        assertTrue(bootstrap.single().asJsonObject["finalQuery"].asBoolean)
    }

    @Test
    fun manualDnsAndRoutingKeepPriorityOverAutomaticSplit() {
        val prefs = Preferences(RuntimeEnvironment.getApplication())
        for (override in
            listOf(
                """{"dns":{"servers":["9.9.9.9"]}}""",
                """{"routing":{"rules":[{"outboundTag":"proxy"}]}}""",
            )) {
            val root =
                JsonParser.parseString(
                        AppConfig(prefs)
                            .buildProfile(source, routing, ProfileOverrides.decode(override))
                    )
                    .asJsonObject
            assertFalse(root.getAsJsonObject("dns").toString().contains("77.88.8.8"))
        }
    }
}
