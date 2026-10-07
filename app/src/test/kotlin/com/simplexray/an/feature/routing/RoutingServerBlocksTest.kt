package com.simplexray.an.feature.routing

import com.google.gson.JsonParser
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingBlock
import com.simplexray.an.feature.routing.model.RoutingBlocks
import com.simplexray.an.feature.routing.model.RoutingServerRef
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.routing.state.RoutingEditor
import org.junit.Assert.*
import org.junit.Test

class RoutingServerBlocksTest {
    private val first = RoutingServerRef("first.json", "Первый")
    private val second = RoutingServerRef("second.json", "Второй")

    @Test
    fun customBlocksKeepSeparateRulesAndStableIds() {
        val a = RoutingBlock(RouteTarget.PROXY, "suffix:example.com", "route_a", first)
        val b = a.copy(id = "route_b", server = second)
        val rules = listOf(a, b).flatMap(RoutingBlocks::parse)
        assertEquals(listOf("route_a", "route_b"), rules.map { it.serverBlockId })
        assertEquals(
            listOf("block_route_a_DOMAIN_0", "block_route_b_DOMAIN_0"),
            rules.map { it.id },
        )
        assertEquals(rules.first(), RoutingBlocks.parse(a.copy(server = second)).single())
        assertEquals(rules.first(), RoutingBlocks.parse(a.copy(server = null)).single())
        assertEquals("Через сервер", rules.first().name)
    }

    @Test
    fun oldBlocksWithoutIdsDecodeWithBuiltinIdsAndVersionOne() {
        val blocks =
            RoutingBlocks.fromRules(emptyList()).map {
                if (it.target == RouteTarget.DIRECT) it.copy(text = "suffix:legacy.example") else it
            }
        val settings =
            RoutingSettings(blocks = blocks, rules = blocks.flatMap(RoutingBlocks::parse))
        val json = JsonParser.parseString(settings.encode()).asJsonObject
        json.getAsJsonArray("blocks").forEach { it.asJsonObject.remove("id") }
        assertEquals(settings, RoutingSettings.decode(json.toString()))
        assertEquals(1, json["version"].asInt)
    }

    @Test
    fun versionTwoPersistsReferencesAndAllowsUnboundImportedBlocks() {
        val ref =
            first.copy(
                subscriptionId = "sub",
                fingerprint = "a".repeat(64),
                matchFingerprint = true,
            )
        val block = RoutingBlock(RouteTarget.PROXY, "suffix:example.com", "route_a", ref)
        val blocks = RoutingBlocks.fromRules(emptyList()) + block
        val settings =
            RoutingSettings(blocks = blocks, rules = blocks.flatMap(RoutingBlocks::parse))
        val raw = settings.encode()
        assertEquals(2, JsonParser.parseString(raw).asJsonObject["version"].asInt)
        assertEquals(settings.copy(version = 2), RoutingSettings.decode(raw))
        val unbound = blocks.map { it.copy(server = null) }
        assertEquals(
            unbound,
            RoutingSettings.decode(settings.copy(blocks = unbound).encode()).blocks,
        )
    }

    @Test
    fun editorAddsAheadOfProxyAndCanReplaceMoveDeleteAndReload() {
        var raw: String? = null
        val editor = RoutingEditor({ raw }, { raw = it })
        assertTrue(editor.saveBlock(RouteTarget.PROXY, "suffix:fallback.example"))
        assertTrue(editor.addServerBlock(first))
        val id = editor.state.value.draft.blocks!!.single { it.isServer }.id
        assertTrue(editor.saveBlock(id, "suffix:special.example"))
        assertEquals(listOf(id, null), editor.state.value.draft.rules.map { it.serverBlockId })
        assertTrue(editor.addServerBlock(second))
        val secondId = editor.state.value.draft.blocks!!.first { it.isServer }.id
        assertNotEquals(id, secondId)
        assertTrue(editor.saveBlock(RouteTarget.PROXY, "suffix:new-fallback.example"))
        assertEquals(
            "suffix:special.example",
            editor.state.value.draft.blocks!!.single { it.id == id }.text,
        )
        assertTrue(editor.selectBlockServer(id, second))
        assertTrue(editor.moveBlock(id, 3))
        val restored = RoutingEditor({ raw }, { raw = it }).state.value.draft
        assertEquals(second, restored.blocks!![3].server)
        assertEquals(id, restored.rules.last().serverBlockId)
        assertFalse(editor.removeBlock(RouteTarget.DIRECT.name))
        assertTrue(editor.removeBlock(id))
        assertTrue(editor.removeBlock(secondId))
        val final = RoutingSettings.decode(raw)
        assertEquals(1, final.version)
        assertEquals(3, final.blocks!!.size)
        assertEquals(listOf("new-fallback.example"), final.rules.single().values)
    }

    @Test
    fun invalidCustomMetadataCannotRedirectRules() {
        val block = RoutingBlock(RouteTarget.PROXY, "suffix:example.com", "route_a", first)
        val blocks = RoutingBlocks.fromRules(emptyList()) + block
        val settings =
            RoutingSettings(blocks = blocks, rules = blocks.flatMap(RoutingBlocks::parse))
        assertThrows(IllegalArgumentException::class.java) {
            settings.copy(blocks = null).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings
                .copy(rules = settings.rules.map { it.copy(serverBlockId = "route_missing") })
                .validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings.copy(blocks = blocks + block.copy(text = "")).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoutingBlocks.parse(block.copy(id = "unsafe:id"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoutingBlocks.parse(block.copy(target = RouteTarget.DIRECT))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoutingBlocks.parse(RoutingBlock(RouteTarget.PROXY, server = first))
        }
    }

    @Test
    fun blockLimitAndRejectedPersistencePreservePreviousState() {
        var raw: String? = null
        val editor = RoutingEditor({ raw }, { raw = it })
        repeat(29) { assertTrue(editor.addServerBlock(first)) }
        val before = raw
        assertFalse(editor.addServerBlock(second))
        assertEquals(before, raw)
        val id = editor.state.value.draft.blocks!!.first { it.isServer }.id
        val broken = RoutingEditor({ raw }, { throw IllegalStateException("disk") })
        assertFalse(broken.selectBlockServer(id, second))
        assertEquals(first, broken.state.value.draft.blocks!!.single { it.id == id }.server)
        assertFalse(broken.removeBlock(id))
        assertEquals(before, raw)
    }
}
