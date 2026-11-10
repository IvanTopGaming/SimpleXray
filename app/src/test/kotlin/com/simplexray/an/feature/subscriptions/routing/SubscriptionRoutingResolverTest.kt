package com.simplexray.an.feature.subscriptions.routing

import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingBlock
import com.simplexray.an.feature.routing.model.RoutingBlocks
import com.simplexray.an.feature.routing.model.RoutingPreset
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.subscriptions.model.Subscription
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubscriptionRoutingResolverTest {
    @get:Rule val temporary = TemporaryFolder()

    private val global =
        RoutingPreset(RoutingSettings(), "https://example.com/geoip.dat", "https://example.com/geosite.dat")

    private fun server(name: String, host: String = "one.example") =
        temporary.newFile(name).apply {
            writeText(
                """{"outbounds":[{"tag":"proxy","protocol":"trojan","settings":{"servers":[{"address":"$host","port":443,"password":"secret"}]}}]}"""
            )
        }

    private fun routing(vararg servers: String?, geosite: String? = null) =
        SubscriptionRouting(
            listOf(SubscriptionRoutingBlock(RouteTarget.DIRECT, "geosite:category-ru")) +
                servers.map { SubscriptionRoutingBlock(RouteTarget.PROXY, "suffix:youtube.com", true, it) },
            geositeUrl = geosite,
        )

    private fun subscription(id: String, vararg files: File) =
        Subscription(id, "Feed", "https://sub.example/$id", 0L, files.map { it.name })

    @Test
    fun serverBlockResolvesProviderRemarkInsideItsSubscription() {
        val frankfurt = server("Feed - DE_ Frankfurt.json", "de.example")
        val other = server("Feed - NL.json")
        val sub = subscription("sub-a", frankfurt, other)
        val resolved =
            SubscriptionRoutingResolver.resolve(routing("DE: Frankfurt"), sub, listOf(sub), listOf(frankfurt, other), global)
        assertTrue(resolved.unresolved.isEmpty())
        assertEquals(mapOf("sub_1" to frankfurt.readText()), resolved.serverSources)
        assertEquals(listOf(null, "sub_1"), resolved.layer.rules.map { it.serverBlockId })
        assertEquals("geosite.dat", resolved.layer.geositeFile)
    }

    @Test
    fun missingDuplicateForeignAndBrokenServersFallBackToActiveProxy() {
        val first = server("Feed - DE.json")
        val second = server("Feed - DE (2).json", "two.example")
        val broken = temporary.newFile("Feed - Broken.json").apply { writeText("{}") }
        val foreign = server("Other - Foreign.json")
        val sub = subscription("sub-a", first, second, broken)
        val otherSub = Subscription("sub-b", "Other", "https://sub.example/b", 0L, listOf(foreign.name))
        val resolved =
            SubscriptionRoutingResolver.resolve(
                routing("DE", "Broken", "Foreign", "Missing", null),
                sub,
                listOf(sub, otherSub),
                listOf(first, second, broken, foreign),
                global,
            )
        assertEquals(listOf("DE", "Broken", "Foreign", "Missing", ""), resolved.unresolved)
        assertTrue(resolved.serverSources.isEmpty())
        assertTrue(resolved.layer.rules.all { it.serverBlockId == null })
        assertEquals(5, resolved.layer.rules.count { it.target == RouteTarget.PROXY })
    }

    @Test
    fun serverIdsNeverCollideWithGlobalBlocks() {
        val frankfurt = server("Feed - DE.json")
        val sub = subscription("sub-a", frankfurt)
        val blocks =
            RoutingBlocks.fromRules(emptyList()) +
                RoutingBlock(RouteTarget.PROXY, "suffix:a.example", "sub_1")
        val taken =
            global.copy(routing = RoutingSettings(version = 2, blocks = blocks, rules = blocks.flatMap(RoutingBlocks::parse)))
        val resolved = SubscriptionRoutingResolver.resolve(routing("DE"), sub, listOf(sub), listOf(frankfurt), taken)
        assertEquals(setOf("sub_1_"), resolved.serverSources.keys)
    }

    @Test
    fun ownGeodataIsUsedOnlyWhenUrlDiffers() {
        val sub = subscription("sub-a")
        val own =
            SubscriptionRoutingResolver.resolve(routing(geosite = "https://provider.example/geosite.dat"), sub, listOf(sub), emptyList(), global)
        assertEquals("sub-sub-a-geosite.dat", own.layer.geositeFile)
        assertEquals("geoip.dat", own.layer.geoipFile)
        assertEquals(mapOf("sub-sub-a-geosite.dat" to "https://provider.example/geosite.dat"), own.geodata)
        val shared =
            SubscriptionRoutingResolver.resolve(routing(geosite = global.geositeUrl), sub, listOf(sub), emptyList(), global)
        assertEquals("geosite.dat", shared.layer.geositeFile)
        assertTrue(shared.geodata.isEmpty())
    }

    @Test
    fun forSourceRespectsOwnershipAndToggle() {
        val dir = temporary.newFolder("files")
        val file = File(dir, "Feed - DE.json").apply { writeText(server("tmp.json").readText()) }
        val sub = subscription("sub-a", file)
        SubscriptionRoutingStore(dir).write(sub.id, routing("DE"))
        assertNotNull(SubscriptionRoutingResolver.forSource(dir, file.name, listOf(sub), global))
        assertNull(SubscriptionRoutingResolver.forSource(dir, "Manual.json", listOf(sub), global))
        assertNull(
            SubscriptionRoutingResolver.forSource(dir, file.name, listOf(sub.copy(routingDisabled = true)), global)
        )
        SubscriptionRoutingStore(dir).delete(sub.id)
        assertNull(SubscriptionRoutingResolver.forSource(dir, file.name, listOf(sub), global))
    }
}
