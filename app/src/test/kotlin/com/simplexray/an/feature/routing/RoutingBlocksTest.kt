package com.simplexray.an.feature.routing

import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingBlock
import com.simplexray.an.feature.routing.model.RoutingBlocks
import com.simplexray.an.feature.routing.model.RoutingRule
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.routing.model.RuleKind
import org.junit.Assert.*
import org.junit.Test

class RoutingBlocksTest {
    @Test
    fun emptyRulesProduceThreeEmptyBlocksInDefaultOrder() {
        val blocks = RoutingBlocks.fromRules(emptyList())
        assertEquals(
            listOf(RouteTarget.DIRECT, RouteTarget.PROXY, RouteTarget.BLOCK),
            blocks.map { it.target },
        )
        assertEquals(blocks.map { it.target }, RoutingBlocks.defaultOrder)
        assertTrue(blocks.all { it.text.isEmpty() && RoutingBlocks.parse(it).isEmpty() })
    }

    @Test
    fun parserNormalizesSupportedPrefixesAndGroupsKindsByFirstAppearance() {
        val rules =
            RoutingBlocks.parse(
                RoutingBlock(
                    RouteTarget.DIRECT,
                    """
                    # Network routes
                    geoip:!RU
                    suffix:ПРИМЕР.РФ
                    full:Exact.Example.COM
                    domain:Other.Example
                    ip:2001:db8::/32
                    geosite:Category-Ads-All@ADS
                    ip:192.0.2.0/24
                    geoip:PRIVATE
                    """
                        .trimIndent(),
                )
            )
        assertEquals(
            listOf(
                RuleKind.GEOIP,
                RuleKind.DOMAIN,
                RuleKind.FULL_DOMAIN,
                RuleKind.IP,
                RuleKind.GEOSITE,
            ),
            rules.map { it.kind },
        )
        assertEquals(listOf("!ru", "private"), rules[0].values)
        assertEquals(listOf("xn--e1afmkfd.xn--p1ai", "other.example"), rules[1].values)
        assertEquals(listOf("exact.example.com"), rules[2].values)
        assertEquals(listOf("2001:db8::/32", "192.0.2.0/24"), rules[3].values)
        assertEquals(listOf("category-ads-all@ads"), rules[4].values)
        assertTrue(
            rules.all { it.target == RouteTarget.DIRECT && it.enabled && it.name == "Напрямую" }
        )
        rules.forEach { it.validate() }
    }

    @Test
    fun parserAcceptsWhitespaceAndMixedLineEndingsButOnlyFullLineComments() {
        val block =
            RoutingBlock(
                RouteTarget.PROXY,
                " \r\n  # ignored\r\n  suffix: example.com \rfull:exact.example\n\t",
            )
        assertEquals(
            listOf(listOf("example.com"), listOf("exact.example")),
            RoutingBlocks.parse(block).map { it.values },
        )
        assertTrue(
            RoutingBlocks.parse(RoutingBlock(RouteTarget.PROXY, "\n # suffix:example.com\r\n"))
                .isEmpty()
        )
        invalidAt("suffix:example.com # comment", 1)
    }

    @Test
    fun errorsKeepOriginalLineNumbersAcrossBlankLinesAndComments() {
        listOf(
                "unknown:example.com",
                "SUFFIX:example.com",
                "suffix",
                "suffix:",
                "regexp:^example$",
                "full:https://example.com",
                "suffix:*.example.com",
                "ip:example.com",
                "ip:192.0.2.1/33",
                "ip:2001:db8::/129",
                "ip:fe80::1%wlan0",
                "geoip:ru@ads",
                "geosite:bad/category",
            )
            .forEach { invalidAt("\n# first\nfull:valid.example\n$it", 4) }
    }

    @Test
    fun ipv6IsNotSplitAtAddressColons() {
        val rules =
            RoutingBlocks.parse(RoutingBlock(RouteTarget.BLOCK, "ip:::1\nip:::/0\nip:2001:db8::1"))
        assertEquals(listOf("::1", "::/0", "2001:db8::1"), rules.single().values)
    }

    @Test
    fun groupedValuesAreChunkedWithoutDroppingOrReorderingThem() {
        val domains = List(129) { "host$it.example" }
        val text = domains.joinToString("\n") { "suffix:$it" }
        val first = RoutingBlocks.parse(RoutingBlock(RouteTarget.DIRECT, text))
        val second = RoutingBlocks.parse(RoutingBlock(RouteTarget.DIRECT, "# Heading\n$text\n"))
        assertEquals(listOf(128, 1), first.map { it.values.size })
        assertEquals(domains, first.flatMap { it.values })
        assertEquals(listOf("block_DIRECT_DOMAIN_0", "block_DIRECT_DOMAIN_1"), first.map { it.id })
        assertEquals(first, second)
        assertNotEquals(
            first.first().id,
            RoutingBlocks.parse(RoutingBlock(RouteTarget.PROXY, "suffix:host0.example")).single().id,
        )
        first.forEach { it.validate() }
    }

    @Test
    fun maximumActiveLineCountIsAcceptedAndOverflowIdentifiesItsSourceLine() {
        val text = List(8192) { "full:host$it.example" }.joinToString("\n")
        val rules = RoutingBlocks.parse(RoutingBlock(RouteTarget.BLOCK, text))
        assertEquals(64, rules.size)
        assertEquals(8192, rules.sumOf { it.values.size })
        RoutingSettings(rules = rules).validate()
        invalidAt("$text\n\n# ignored\nfull:overflow.example", 8195)
    }

    @Test
    fun rawTextLimitIncludesComments() {
        assertTrue(
            RoutingBlocks.parse(RoutingBlock(RouteTarget.BLOCK, "#" + "x".repeat(1_999_999)))
                .isEmpty()
        )
        invalidAt("#" + "x".repeat(2_000_000), 1)
    }

    @Test
    fun combinedRuleLimitRemainsTheSettingsResponsibility() {
        val text = List(8192) { "full:host$it.example" }.joinToString("\n")
        val rules =
            RoutingBlocks.parse(RoutingBlock(RouteTarget.DIRECT, text)) +
                RoutingBlocks.parse(RoutingBlock(RouteTarget.PROXY, "full:extra.example"))
        assertEquals(65, rules.size)
        assertThrows(IllegalArgumentException::class.java) {
            RoutingSettings(rules = rules).validate()
        }
    }

    @Test
    fun conversionUsesFirstEnabledTargetAppearanceAndAppendsMissingTargets() {
        val rules =
            listOf(
                rule(RouteTarget.DIRECT, "disabled.example").copy(enabled = false),
                rule(RouteTarget.BLOCK, "blocked.example"),
                rule(RouteTarget.PROXY, "proxy.example"),
            )
        val blocks = RoutingBlocks.fromRules(rules)
        assertEquals(
            listOf(RouteTarget.BLOCK, RouteTarget.PROXY, RouteTarget.DIRECT),
            blocks.map { it.target },
        )
        assertEquals(
            listOf("blocked.example", "proxy.example"),
            blocks.flatMap { RoutingBlocks.parse(it) }.flatMap { it.values },
        )
        assertEquals(
            listOf(RouteTarget.PROXY, RouteTarget.DIRECT, RouteTarget.BLOCK),
            RoutingBlocks.fromRules(listOf(rule(RouteTarget.PROXY, "one.example"))).map {
                it.target
            },
        )
    }

    @Test
    fun conversionEmitsCanonicalPrefixesAndRetainsRuleNamesAsComments() {
        val originals =
            listOf(
                rule(RouteTarget.PROXY, "example.com", RuleKind.DOMAIN).copy(name = "Suffix rule"),
                rule(RouteTarget.PROXY, "exact.example", RuleKind.FULL_DOMAIN),
                rule(RouteTarget.PROXY, "192.0.2.0/24", RuleKind.IP),
                rule(RouteTarget.PROXY, "private", RuleKind.GEOIP),
                rule(RouteTarget.PROXY, "category-ads-all", RuleKind.GEOSITE),
            )
        val block = RoutingBlocks.fromRules(originals).first()
        assertTrue(block.text.lines().contains("# Suffix rule"))
        assertEquals(
            listOf(
                "suffix:example.com",
                "full:exact.example",
                "ip:192.0.2.0/24",
                "geoip:private",
                "geosite:category-ads-all",
            ),
            block.text.lines().filter { it.isNotBlank() && !it.startsWith("#") },
        )
        val parsed = RoutingBlocks.parse(block)
        assertEquals(originals.map { it.kind to it.values }, parsed.map { it.kind to it.values })
    }

    @Test
    fun disabledRulesAndMultilineNamesNeverActivateOnConversion() {
        val disabled =
            rule(RouteTarget.DIRECT, "disabled.example")
                .copy(
                    name = "Old rule\nfull:injected.example\rgeoip:private",
                    enabled = false,
                )
        val enabled =
            rule(RouteTarget.DIRECT, "allowed.example")
                .copy(name = "Active\r\nfull:also-injected.example")
        val block = RoutingBlocks.fromRules(listOf(disabled, enabled)).first()
        assertTrue(block.text.lines().contains("# suffix:disabled.example"))
        assertTrue(block.text.lines().contains("# full:injected.example"))
        assertTrue(block.text.lines().contains("# geoip:private"))
        assertTrue(block.text.lines().contains("# full:also-injected.example"))
        assertEquals(listOf("allowed.example"), RoutingBlocks.parse(block).single().values)
    }

    @Test
    fun disabledValueLinesRemainCommentsEvenForMalformedLegacyText() {
        val disabled =
            rule(RouteTarget.DIRECT, "disabled.example\nfull:injected.example")
                .copy(enabled = false)
        val block = RoutingBlocks.fromRules(listOf(disabled)).first()
        assertTrue(RoutingBlocks.parse(block).isEmpty())
    }

    @Test
    fun interleavingOnlyConsidersEnabledTargetTransitions() {
        val direct = rule(RouteTarget.DIRECT, "direct.example")
        val proxy = rule(RouteTarget.PROXY, "proxy.example")
        val blocked = rule(RouteTarget.BLOCK, "blocked.example")
        assertFalse(RoutingBlocks.hasInterleavedTargets(emptyList()))
        assertFalse(RoutingBlocks.hasInterleavedTargets(listOf(direct, direct, proxy, blocked)))
        assertFalse(
            RoutingBlocks.hasInterleavedTargets(listOf(direct, proxy.copy(enabled = false), direct))
        )
        assertFalse(
            RoutingBlocks.hasInterleavedTargets(listOf(direct.copy(enabled = false), proxy, direct))
        )
        assertTrue(RoutingBlocks.hasInterleavedTargets(listOf(direct, proxy, direct)))
        assertTrue(RoutingBlocks.hasInterleavedTargets(listOf(direct, proxy, blocked, proxy)))
    }

    private fun invalidAt(text: String, line: Int) {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                RoutingBlocks.parse(RoutingBlock(RouteTarget.DIRECT, text))
            }
        assertTrue(error.message, error.message.orEmpty().startsWith("Строка $line: "))
    }

    private fun rule(target: RouteTarget, value: String, kind: RuleKind = RuleKind.DOMAIN) =
        RoutingRule(name = "Saved rule", kind = kind, values = listOf(value), target = target)
}
