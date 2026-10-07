package com.simplexray.an.feature.routing

import com.google.gson.JsonParser
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingBlocks
import com.simplexray.an.feature.routing.model.RoutingRule
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.routing.state.RoutingEditor
import org.junit.Assert.*
import org.junit.Test

class RoutingBlockPersistenceTest {
    @Test
    fun legacyReadDoesNotRegroupRulesUntilExplicitSave() {
        val first =
            RoutingRule(
                id = "first",
                name = "First",
                target = RouteTarget.DIRECT,
                values = listOf("first.test"),
            )
        val second =
            first.copy(
                id = "second",
                name = "Second",
                target = RouteTarget.PROXY,
                values = listOf("second.test"),
            )
        val third = first.copy(id = "third", name = "Third", values = listOf("third.test"))
        var raw = RoutingSettings(rules = listOf(first, second, third)).encode()
        val original = raw
        val editor = RoutingEditor({ raw }, { raw = it })
        assertEquals(original, raw)
        assertEquals(listOf(first, second, third), editor.state.value.draft.rules)
        assertTrue(RoutingBlocks.hasInterleavedTargets(editor.state.value.draft.rules))
        assertTrue(editor.saveBlock(RouteTarget.BLOCK, "geosite:category-ads-all"))
        val restored = RoutingSettings.decode(raw)
        assertEquals(
            listOf(RouteTarget.DIRECT, RouteTarget.PROXY, RouteTarget.BLOCK),
            restored.blocks!!.map { it.target },
        )
        assertEquals(listOf("first.test", "third.test"), restored.rules.first().values)
        assertEquals(RouteTarget.BLOCK, restored.rules.last().target)
    }

    @Test
    fun textAndEmptyBlockOrderSurviveReopeningAndLegacyReader() {
        var raw: String? = null
        val editor = RoutingEditor({ raw }, { raw = it })
        val text = "# Keep this note\ngeoip:ru\ngeosite:google\nsuffix:ipwho.is\n"
        assertTrue(editor.saveBlock(RouteTarget.DIRECT, text))
        assertTrue(editor.moveBlock(RouteTarget.BLOCK, 0))
        val restored = RoutingEditor({ raw }, { raw = it }).state.value.draft
        assertEquals(text, restored.blocks!!.single { it.target == RouteTarget.DIRECT }.text)
        assertEquals(RouteTarget.BLOCK, restored.blocks!!.first().target)
        val legacy = JsonParser.parseString(raw).asJsonObject.apply { remove("blocks") }
        assertEquals(restored.rules, RoutingSettings.decode(legacy.toString()).rules)
    }

    @Test
    fun invalidBlockAndFailedWritePreserveSavedRules() {
        var raw: String? = null
        val editor = RoutingEditor({ raw }, { raw = it })
        assertTrue(editor.saveBlock(RouteTarget.PROXY, "suffix:example.com"))
        val before = raw
        assertFalse(editor.saveBlock(RouteTarget.DIRECT, "geoip:ru\nbroken"))
        assertEquals(before, raw)
        assertTrue(editor.state.value.error.orEmpty().contains("2"))
        val broken = RoutingEditor({ raw }, { throw IllegalStateException("storage") })
        assertFalse(broken.saveBlock(RouteTarget.BLOCK, "ip:127.0.0.1"))
        assertEquals(RoutingSettings.decode(before), broken.state.value.draft)
    }

    @Test
    fun inconsistentTextMetadataIsRejectedInsteadOfChangingRuntimeSilently() {
        var raw: String? = null
        val editor = RoutingEditor({ raw }, { raw = it })
        assertTrue(editor.saveBlock(RouteTarget.DIRECT, "suffix:example.com"))
        val json = JsonParser.parseString(raw).asJsonObject
        json.getAsJsonArray("blocks")[0].asJsonObject.addProperty("text", "suffix:other.test")
        assertThrows(IllegalArgumentException::class.java) {
            RoutingSettings.decode(json.toString())
        }
    }
}
