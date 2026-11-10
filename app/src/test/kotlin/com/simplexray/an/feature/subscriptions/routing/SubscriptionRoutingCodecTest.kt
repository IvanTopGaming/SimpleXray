package com.simplexray.an.feature.subscriptions.routing

import com.simplexray.an.feature.routing.model.RouteTarget
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SubscriptionRoutingCodecTest {
    private fun link(json: String) =
        "simplexray://routing/" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    private val provider =
        """{"format":"simplexray-routing","version":2,"routing":{"defaultRoute":"DIRECT","bypassLan":false,"domainStrategy":"IP_ON_DEMAND","blocks":[{"target":"DIRECT","text":"geoip:ru\nsuffix:example.ru"},{"target":"PROXY","text":"suffix:youtube.com","server":{"name":"DE: Frankfurt"}},{"target":"BLOCK","text":"geosite:category-ads-all"}]},"geositeUrl":"https://example.com/geosite.dat"}"""

    @Test
    fun headerWinsAndForeignRoutingLinksAreIgnored() {
        val ours = link(provider)
        val body = "vless://server\n${link("""{"format":"other"}""")}"
        assertEquals(ours, SubscriptionRoutingCodec.link(listOf("happ://routing/onadd/abc\n$ours"), body))
        assertEquals(ours, SubscriptionRoutingCodec.link(listOf("happ://routing/add/x", ours), ""))
        assertNull(SubscriptionRoutingCodec.link(listOf("happ://routing/onadd/abc"), "vless://a"))
        assertNull(SubscriptionRoutingCodec.link(emptyList(), "[{\"outbounds\":[]}]"))
    }

    @Test
    fun bodyLineIsFoundInPlainAndBase64Subscriptions() {
        val ours = link(provider)
        val plain = "vless://one\n  $ours  \ntrojan://two"
        assertEquals(ours, SubscriptionRoutingCodec.link(emptyList(), plain))
        val encoded = Base64.getEncoder().encodeToString(plain.toByteArray())
        assertEquals(ours, SubscriptionRoutingCodec.link(emptyList(), encoded))
    }

    @Test
    fun providerPresetKeepsOnlyBlocksAndGeodata() {
        val routing = SubscriptionRoutingCodec.decodeLink(link(provider))
        assertEquals(
            listOf(
                SubscriptionRoutingBlock(RouteTarget.DIRECT, "geoip:ru\nsuffix:example.ru"),
                SubscriptionRoutingBlock(RouteTarget.PROXY, "suffix:youtube.com", true, "DE: Frankfurt"),
                SubscriptionRoutingBlock(RouteTarget.BLOCK, "geosite:category-ads-all"),
            ),
            routing.blocks,
        )
        assertNull(routing.geoipUrl)
        assertEquals("https://example.com/geosite.dat", routing.geositeUrl)
        assertEquals(4, routing.rules { "sub_$it" }.size)
    }

    @Test
    fun exportedUserPresetIsAccepted() {
        val exported =
            """{"format":"simplexray-routing","version":2,"routing":{"version":2,"enabled":true,"defaultRoute":"PROXY","bypassLan":true,"domainStrategy":"AS_IS","rules":[],"blocks":[{"target":"PROXY","text":"suffix:a.example","id":"route_x"},{"target":"DIRECT","text":"","id":"DIRECT"},{"target":"PROXY","text":"","id":"PROXY"},{"target":"BLOCK","text":"","id":"BLOCK"}]},"geoipUrl":"https://example.com/geoip.dat","geositeUrl":"https://example.com/geosite.dat"}"""
        val routing = SubscriptionRoutingCodec.decode(exported)
        assertEquals(SubscriptionRoutingBlock(RouteTarget.PROXY, "suffix:a.example", true), routing.blocks.first())
    }

    @Test
    fun invalidPresetsAreRejected() {
        listOf(
                """{"format":"other","version":2,"routing":{"blocks":[]}}""",
                """{"format":"simplexray-routing","version":3,"routing":{"blocks":[{"target":"DIRECT","text":""}]}}""",
                """{"format":"simplexray-routing","version":1,"routing":{"blocks":[{"target":"PROXY","text":"","server":{"name":"A"}}]}}""",
                """{"format":"simplexray-routing","version":2,"routing":{"blocks":[{"target":"DIRECT","text":"","server":{"name":"A"}}]}}""",
                """{"format":"simplexray-routing","version":2,"routing":{"blocks":[{"target":"DIRECT","text":"bad"}]}}""",
                """{"format":"simplexray-routing","version":2,"routing":{"blocks":[{"target":"DIRECT","text":""},{"target":"DIRECT","text":""}]}}""",
                """{"format":"simplexray-routing","version":2,"routing":{"blocks":[{"target":"DIRECT","text":""}]},"geoipUrl":"ftp://x"}""",
            )
            .forEach { raw ->
                assertThrows(raw, IllegalArgumentException::class.java) {
                    SubscriptionRoutingCodec.decode(raw)
                }
            }
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionRoutingCodec.decodeLink("simplexray://routing/!!!")
        }
    }
}
