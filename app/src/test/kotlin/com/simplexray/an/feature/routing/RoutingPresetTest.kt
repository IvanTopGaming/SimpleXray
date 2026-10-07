package com.simplexray.an.feature.routing

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.feature.routing.model.DomainStrategy
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingBlock
import com.simplexray.an.feature.routing.model.RoutingBlocks
import com.simplexray.an.feature.routing.model.RoutingPreset
import com.simplexray.an.feature.routing.model.RoutingRule
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.routing.model.RuleKind
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class RoutingPresetTest {
    private val geoip = "https://assets.example/geoip.dat?release=1"
    private val geosite = "http://127.0.0.1:8080/geosite.dat"

    @Test
    fun legacyRulesRoundTripWithoutRegroupingOrReenablingDisabledRules() {
        val rules =
            listOf(
                RoutingRule(
                    "first",
                    "Точный домен",
                    RuleKind.FULL_DOMAIN,
                    listOf("one.example"),
                    RouteTarget.PROXY,
                ),
                RoutingRule(
                    "second",
                    "Direct",
                    RuleKind.IP,
                    listOf("192.0.2.0/24"),
                    RouteTarget.DIRECT,
                ),
                RoutingRule(
                    "third",
                    "Off",
                    RuleKind.GEOSITE,
                    listOf("private"),
                    RouteTarget.BLOCK,
                    false,
                ),
                RoutingRule(
                    "fourth",
                    "Proxy again",
                    RuleKind.DOMAIN,
                    listOf("two.example"),
                    RouteTarget.PROXY,
                ),
            )
        val settings =
            RoutingSettings(
                defaultRoute = RouteTarget.BLOCK,
                bypassLan = false,
                domainStrategy = DomainStrategy.IP_ON_DEMAND,
                rules = rules,
            )
        val preset = RoutingPreset(settings, geoip, geosite)
        val encoded = preset.encode()
        assertEquals(preset, RoutingPreset.decode(encoded))
        assertEquals(preset, RoutingPreset.detect(encoded))
        val json = JsonParser.parseString(encoded).asJsonObject
        assertEquals("simplexray-routing", json["format"].asString)
        assertEquals(
            listOf("format", "version", "routing", "geoipUrl", "geositeUrl"),
            json.keySet().toList(),
        )
        assertEquals("1", json["version"].toString())
        assertNull(RoutingPreset.decode(encoded).routing.blocks)
    }

    @Test
    fun textBlocksPreserveCommentsBlankLinesOrderAndSavedTextExactly() {
        val blocks =
            listOf(
                RoutingBlock(
                    RouteTarget.BLOCK,
                    "# Мой список\r\ngeosite:category-ads-all\r\n\r\n# full:disabled.example",
                ),
                RoutingBlock(RouteTarget.PROXY, "  # Защита\n suffix:Example.COM \n"),
                RoutingBlock(RouteTarget.DIRECT, ""),
            )
        val settings =
            RoutingSettings(
                defaultRoute = RouteTarget.DIRECT,
                bypassLan = true,
                blocks = blocks,
                rules = blocks.flatMap(RoutingBlocks::parse),
            )
        val preset = RoutingPreset(settings, geoip, geosite)
        assertEquals(preset, RoutingPreset.decode(preset.encode()))
        assertEquals(blocks, RoutingPreset.decode(preset.encode()).routing.blocks)
    }

    @Test
    fun selfContainedLinkPreservesUnicodeCommentsOrderAndSourceUrls() {
        val blocks =
            listOf(
                RoutingBlock(
                    RouteTarget.BLOCK,
                    "# Блокировка рекламы 🔒\ngeosite:category-ads-all",
                ),
                RoutingBlock(RouteTarget.DIRECT, "# Локальные адреса\nip:192.0.2.0/24"),
                RoutingBlock(RouteTarget.PROXY),
            )
        val expected =
            RoutingPreset(
                RoutingSettings(
                    defaultRoute = RouteTarget.BLOCK,
                    bypassLan = false,
                    blocks = blocks,
                    rules = blocks.flatMap(RoutingBlocks::parse),
                ),
                geoip,
                geosite,
            )
        val link = expected.encodeLink()
        assertTrue(link.startsWith("simplexray://routing/"))
        val payload = link.removePrefix("simplexray://routing/")
        assertTrue(payload.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(
            expected.encode(),
            String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8),
        )
        assertEquals(expected, RoutingPreset.detect(link))
        assertEquals(expected, RoutingPreset.detect(" \n$link\r\n"))
    }

    @Test
    fun malformedRoutingLinksThrowInsteadOfBecomingServerImports() {
        val valid = preset().encodeLink()
        listOf(
                "simplexray://routing",
                "simplexray://routing/",
                "simplexray://routing/A",
                "simplexray://routing/%%%%",
                "simplexray://routing/a+b/",
                "$valid=",
                "$valid?source=copy",
                "$valid#fragment",
                "simplexray://routing/" +
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(byteArrayOf(0xc3.toByte(), 0x28)),
                "simplexray://routing/" +
                    Base64.getUrlEncoder().withoutPadding().encodeToString("{}".toByteArray()),
            )
            .forEach {
                assertThrows(it, IllegalArgumentException::class.java) { RoutingPreset.detect(it) }
            }
        assertNull(RoutingPreset.detect("simplexray://server/anything"))
        assertNull(RoutingPreset.detect("simplexray://routing-other/anything"))
    }

    @Test
    fun routingLinkRequiresCanonicalBase64UrlTrailingBits() {
        var json = preset().encode()
        while (json.toByteArray(Charsets.UTF_8).size % 3 != 1) json += " "
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val encoded =
            Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
        val changed = encoded.dropLast(1) + alphabet[alphabet.indexOf(encoded.last()) + 1]
        assertThrows(IllegalArgumentException::class.java) {
            RoutingPreset.detect("simplexray://routing/$changed")
        }
    }

    @Test
    fun linkBoundsAccountForBase64ExpansionAndDecodedUtf8Bytes() {
        val prefix = preset().encode().dropLast(1) + ",\"padding\":\""
        val json = prefix + "x".repeat(8 * 1024 * 1024 - prefix.length - 2) + "\"}"
        val link =
            "simplexray://routing/" +
                Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(json.toByteArray(Charsets.UTF_8))
        assertTrue(link.length > 8 * 1024 * 1024)
        assertEquals(preset(), RoutingPreset.detect(link))
        assertThrows(IllegalArgumentException::class.java) { RoutingPreset.detect(link + "A") }
        val oversized = "simplexray://routing/" + "A".repeat((8 * 1024 * 1024 + 1) * 4 / 3)
        assertThrows(IllegalArgumentException::class.java) { RoutingPreset.detect(oversized) }
    }

    @Test
    fun ordinaryServersAndSubscriptionsAreNotRoutingPresets() {
        listOf(
                "vless://identifier@server.example:443#Server",
                "dmxlc3M6Ly9pZGVudGlmaWVyQHNlcnZlci5leGFtcGxlOjQ0Mw==",
                "{\"outbounds\":[{\"protocol\":\"vless\"}]}",
                "[{\"remarks\":\"simplexray-routing\",\"outbounds\":[]}]",
                "{\"remarks\":\"simplexray-routing\",\"outbounds\":[]}",
                "{\"format\":\"some-other-format\",\"version\":1}",
                "{\"nested\":{\"format\":\"simplexray-routing\"}}",
                "",
            )
            .forEach { assertNull(it, RoutingPreset.detect(it)) }
    }

    @Test
    fun identifiedMalformedPresetsNeverFallThroughToServerImport() {
        val encoded = preset().encode()
        listOf(
                "{\"format\":\"simplexray-routing\",",
                "{\"format\":\"simplexray-routing\"}",
                encoded + " trailing-data",
                encoded.replace("\"version\":1", "\"version\":99"),
                encoded.replace("\"version\":1", "\"version\":\"1\""),
                encoded.replace(
                    "\"format\":\"simplexray-routing\"",
                    "\"format\":[\"simplexray-routing\"]",
                ),
            )
            .forEach {
                assertThrows(IllegalArgumentException::class.java) { RoutingPreset.detect(it) }
            }
    }

    @Test
    fun decodeRejectsMissingFieldsUnknownVersionsAndInvalidTypes() {
        listOf("format", "version", "routing", "geoipUrl", "geositeUrl").forEach { key ->
            rejected { it.remove(key) }
        }
        rejected { it.addProperty("format", "other") }
        rejected { it.addProperty("version", 3) }
        rejected { it.addProperty("version", "1") }
        rejected { it.addProperty("version", 1.0) }
        rejected { it.addProperty("routing", "{}") }
        rejected { it.add("routing", JsonNull.INSTANCE) }
        rejected { it.addProperty("geoipUrl", 123) }
        rejected { it.addProperty("geositeUrl", false) }
        rejected { it.add("geoipUrl", JsonNull.INSTANCE) }
        rejected { it.getAsJsonObject("routing").addProperty("bypassLan", "false") }
        rejected { it.getAsJsonObject("routing").addProperty("version", 3) }
    }

    @Test
    fun sourceUrlsMustBeHttpUrlsAndErrorsDoNotEchoTheirContents() {
        listOf(
                "",
                "file:///private/secret",
                "ftp://host.example/geosite.dat",
                "https://",
                " https://host.example/file",
                "https://host.example/secret\nvalue",
            )
            .forEach { bad ->
                val error =
                    assertThrows(IllegalArgumentException::class.java) {
                        RoutingPreset(RoutingSettings(), bad, geosite).validate()
                    }
                if (bad.isNotEmpty()) assertFalse(error.message.orEmpty().contains(bad))
                assertThrows(IllegalArgumentException::class.java) {
                    RoutingPreset(RoutingSettings(), geoip, bad).encode()
                }
            }
        RoutingPreset(
                RoutingSettings(),
                "https://[2001:db8::1]/geoip.dat",
                "http://localhost/geosite.dat",
            )
            .validate()
    }

    @Test
    fun geodataUrlLengthIsBoundedBeforeStagingEitherSource() {
        val prefix = "https://assets.example/data?token="
        val boundary = prefix + "x".repeat(8192 - prefix.length)
        RoutingPreset(RoutingSettings(), boundary, boundary).validate()
        val oversized = boundary + "x"
        listOf("geoipUrl", "geositeUrl").forEach { field ->
            val json = JsonParser.parseString(preset().encode()).asJsonObject
            json.addProperty(field, oversized)
            assertThrows(IllegalArgumentException::class.java) {
                RoutingPreset.detect(json.toString())
            }
            val invalid =
                if (field == "geoipUrl") preset().copy(geoipUrl = oversized)
                else preset().copy(geositeUrl = oversized)
            assertThrows(IllegalArgumentException::class.java) { invalid.validate() }
            assertThrows(IllegalArgumentException::class.java) { invalid.encode() }
        }
    }

    @Test
    fun malformedRoutingAndTextRuleDisagreementsAreRejectedBeforeImport() {
        val blocks =
            listOf(
                RoutingBlock(RouteTarget.DIRECT, "full:direct.example"),
                RoutingBlock(RouteTarget.PROXY),
                RoutingBlock(RouteTarget.BLOCK),
            )
        val preset =
            RoutingPreset(
                RoutingSettings(blocks = blocks, rules = blocks.flatMap(RoutingBlocks::parse)),
                geoip,
                geosite,
            )
        val json = JsonParser.parseString(preset.encode()).asJsonObject
        json
            .getAsJsonObject("routing")
            .getAsJsonArray("blocks")[0]
            .asJsonObject
            .addProperty("text", "full:changed.example")
        assertThrows(IllegalArgumentException::class.java) { RoutingPreset.decode(json.toString()) }
        assertThrows(IllegalArgumentException::class.java) {
            preset.copy(routing = preset.routing.copy(rules = emptyList())).validate()
        }
        rejected { it.getAsJsonObject("routing").addProperty("rules", "invalid") }
    }

    @Test
    fun duplicateEnvelopeFieldsAndNonJsonSyntaxAreRejected() {
        val encoded = preset().encode()
        listOf(
                encoded.replace("\"version\":1", "\"version\":1,\"version\":1"),
                encoded.replace("\"format\":", "format:"),
                encoded.replace("\"version\":1", "\"version\":1/* comment */"),
            )
            .forEach {
                assertThrows(IllegalArgumentException::class.java) { RoutingPreset.decode(it) }
            }
    }

    @Test
    fun inputLimitsCountUtf8BytesAsWellAsCharacters() {
        val oversized =
            "{\"format\":\"simplexray-routing\",\"padding\":\"" +
                "x".repeat(8 * 1024 * 1024) +
                "\"}"
        assertThrows(IllegalArgumentException::class.java) { RoutingPreset.decode(oversized) }
        assertThrows(IllegalArgumentException::class.java) { RoutingPreset.detect(oversized) }
        val multibyte =
            "{\"format\":\"simplexray-routing\",\"padding\":\"" +
                "я".repeat(4 * 1024 * 1024) +
                "\"}"
        assertTrue(multibyte.length < 8 * 1024 * 1024)
        assertThrows(IllegalArgumentException::class.java) { RoutingPreset.decode(multibyte) }
    }

    @Test
    fun exportRejectsOversizedPayloads() {
        val oversizedUrl = "https://assets.example/" + "x".repeat(8 * 1024 * 1024)
        assertThrows(IllegalArgumentException::class.java) {
            preset().copy(geoipUrl = oversizedUrl).encode()
        }
    }

    @Test
    fun excessiveJsonNestingIsRejectedWithoutStackOverflowOrFallback() {
        val nested =
            "{\"format\":\"simplexray-routing\",\"padding\":" +
                "[".repeat(10000) +
                "0" +
                "]".repeat(10000) +
                "}"
        assertThrows(IllegalArgumentException::class.java) { RoutingPreset.decode(nested) }
        assertThrows(IllegalArgumentException::class.java) { RoutingPreset.detect(nested) }
    }

    @Test
    fun boundedReaderPreservesUtf8AndLeavesTheStreamOpen() {
        val encoded = preset().encode()
        var closed = false
        val input =
            object : ByteArrayInputStream(encoded.toByteArray(Charsets.UTF_8)) {
                override fun close() {
                    closed = true
                }
            }
        assertEquals(encoded, RoutingPreset.readText(input))
        assertFalse(closed)
        assertEquals(preset(), RoutingPreset.decode("\uFEFF$encoded"))
        assertEquals(preset(), RoutingPreset.detect("\uFEFF$encoded"))
    }

    @Test
    fun boundedReaderRejectsMalformedUtf8AndStopsAfterOneExcessByte() {
        assertThrows(IllegalArgumentException::class.java) {
            RoutingPreset.readText(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
        var reads = 0
        var closed = false
        val unlimited =
            object : InputStream() {
                override fun read(): Int {
                    reads++
                    return 'x'.code
                }

                override fun close() {
                    closed = true
                }
            }
        assertThrows(IllegalArgumentException::class.java) { RoutingPreset.readText(unlimited) }
        assertTrue("Reader consumed $reads bytes", reads <= 8 * 1024 * 1024 + 1)
        assertFalse(closed)
    }

    @Test
    fun boundedReaderAcceptsTheExactByteLimit() {
        val input = ByteArray(8 * 1024 * 1024) { 'x'.code.toByte() }
        assertEquals(input.size, RoutingPreset.readText(ByteArrayInputStream(input)).length)
    }

    private fun preset() = RoutingPreset(RoutingSettings(), geoip, geosite)

    private fun rejected(change: (JsonObject) -> Unit) {
        val json = JsonParser.parseString(preset().encode()).asJsonObject
        change(json)
        assertThrows(IllegalArgumentException::class.java) { RoutingPreset.decode(json.toString()) }
    }
}
