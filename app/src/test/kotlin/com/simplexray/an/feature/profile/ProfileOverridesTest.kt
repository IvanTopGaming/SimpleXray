package com.simplexray.an.feature.profile

import com.google.gson.JsonParser
import com.simplexray.an.core.config.dns.DnsConfigCompiler
import com.simplexray.an.core.config.ownership.OwnedConfig
import com.simplexray.an.core.config.profile.ProfileGeodata
import com.simplexray.an.core.config.profile.ProfileOverrides
import com.simplexray.an.core.config.routing.RoutingCompiler
import com.simplexray.an.feature.dns.model.DnsSettings
import com.simplexray.an.feature.routing.model.RoutingRule
import com.simplexray.an.feature.routing.model.RoutingSettings
import org.junit.Assert.*
import org.junit.Test

class ProfileOverridesTest {
    private fun config(): String =
        RoutingCompiler.compile(
            DnsConfigCompiler.compile(
                OwnedConfig.build(
                    """{"outbounds":[{"protocol":"socks","tag":"proxy","settings":{"servers":[{"address":"127.0.0.1","port":12345}]}}]}""",
                    "127.0.0.1",
                    10808,
                ),
                DnsSettings(),
                false,
            ),
            RoutingSettings(
                rules =
                    listOf(RoutingRule(id = "old", name = "Old", values = listOf("old.example")))
            ),
        )

    @Test
    fun commaSeparatedDnsNetworksNormalizeAndTrackEachDatabaseReference() {
        val overrides =
            ProfileOverrides.decode(
                """{"dns":{"servers":[{"address":"1.1.1.1","expectedIPs":"192.0.2.0/24,geoip:ru","unexpectedIPs":"*,geoip:!cn"}]}}"""
            )
        val settings =
            JsonParser.parseString(overrides.encode())
                .asJsonObject
                .getAsJsonObject("dns")
                .getAsJsonArray("servers")[0]
                .asJsonObject
        assertEquals(
            listOf("192.0.2.0/24", "geoip:ru"),
            settings.getAsJsonArray("expectedIPs").map { it.asString },
        )
        assertEquals(setOf("geoip.dat"), ProfileGeodata.requiredFiles(overrides.applyTo(config())))
    }

    @Test
    fun emptyOverrideIsExactIdentity() {
        val source = config()
        assertEquals(source, ProfileOverrides.decode(null).applyTo(source))
        assertEquals(source, ProfileOverrides.decode("{}").applyTo(source))
        assertEquals("{}", ProfileOverrides.decode("").encode())
    }

    @Test
    fun replacingRoutingKeepsDnsAndFakeIpProtectionButReplacesUserRules() {
        val result =
            ProfileOverrides.decode(
                    """{"routing":{"domainStrategy":"AsIs","rules":[{"type":"field","domain":["geosite:google"],"outboundTag":"proxy"}]}}"""
                )
                .applyTo(config())
        val root = JsonParser.parseString(result).asJsonObject
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules")
        assertTrue(rules.any { it.asJsonObject["port"]?.asString == "53" })
        assertTrue(
            rules.any { it.asJsonObject["ruleTag"]?.asString == DnsConfigCompiler.GUARD_RULE }
        )
        assertTrue(result.contains("geosite:google"))
        assertFalse(result.contains("old.example"))
        assertEquals(setOf("geosite.dat"), ProfileGeodata.requiredFiles(result))
    }

    @Test
    fun dnsReplacementRetainsOwnedTagAndFindsDnsOnlyGeodata() {
        val source = config()
        val old = JsonParser.parseString(source).asJsonObject.getAsJsonObject("dns")["tag"].asString
        val result =
            ProfileOverrides.decode(
                    """{"dns":{"servers":[{"address":"1.1.1.1","domains":["geosite:google"],"expectIPs":["geoip:us"]}],"hosts":{"geosite:private":"127.0.0.1"}}}"""
                )
                .applyTo(source)
        val dns = JsonParser.parseString(result).asJsonObject.getAsJsonObject("dns")
        assertEquals(old, dns["tag"].asString)
        assertEquals("1.1.1.1", dns.getAsJsonArray("servers")[0].asJsonObject["address"].asString)
        assertEquals(setOf("geoip.dat", "geosite.dat"), ProfileGeodata.requiredFiles(result))
    }

    @Test
    fun invalidSectionsAndFieldTypesRejectBeforeApplication() {
        listOf(
                "[]",
                "null",
                "{\"api\":{}}",
                "{\"inbounds\":[]}",
                "{\"log\":null}",
                "{\"log\":{\"loglevel\":\"loud\"}}",
                "{\"log\":{\"access\":\"/private/preferences.xml\"}}",
                "{\"policy\":{\"system\":{\"statsInboundUplink\":\"false\"}}}",
                "{\"routing\":{\"rules\":{}}}",
                "{\"dns\":{\"servers\":[23]}}",
                "{\"log\":{},\"log\":{}}",
                "{\"stats\":[]}",
            )
            .forEach { raw ->
                assertThrows(raw, IllegalArgumentException::class.java) {
                    ProfileOverrides.decode(raw)
                }
            }
    }

    @Test
    fun unknownOutboundAndReservedDnsTagReject() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileOverrides.decode(
                    """{"routing":{"rules":[{"type":"field","domain":["example.com"],"outboundTag":"missing"}]}}"""
                )
                .applyTo(config())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProfileOverrides.decode("""{"dns":{"tag":"__sx_client","servers":["1.1.1.1"]}}""")
                .applyTo(config())
        }
    }

    @Test
    fun fakeIpWithManualIpRulesResolvesDomainsBeforeMatching() {
        val result =
            ProfileOverrides.decode(
                    """{"routing":{"domainStrategy":"AsIs","rules":[{"type":"field","ip":["geoip:ru"],"outboundTag":"proxy"}]}}"""
                )
                .applyTo(config())
        assertEquals(
            "IPOnDemand",
            JsonParser.parseString(result)
                .asJsonObject
                .getAsJsonObject("routing")["domainStrategy"]
                .asString,
        )
        assertEquals(setOf("geoip.dat"), ProfileGeodata.requiredFiles(result))
    }

    @Test
    fun geodataDetectionIgnoresCredentialsAndRejectsUnsupportedExternalFiles() {
        assertTrue(
            ProfileGeodata.requiredFiles(
                    """{"outbounds":[{"settings":{"password":"geosite:google"}}]}"""
                )
                .isEmpty()
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProfileOverrides.decode(
                """{"routing":{"rules":[{"domain":["ext:private.dat:test"],"outboundTag":"proxy"}]}}"""
            )
        }
    }
}
