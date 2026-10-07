package com.simplexray.an.feature.servers

import com.simplexray.an.feature.servers.model.ServerCheckResult
import com.simplexray.an.feature.servers.model.ServerDetails
import com.simplexray.an.feature.servers.ui.ServerGrouping
import com.simplexray.an.feature.servers.ui.configGroups
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ConfigListModelTest {
    private val france = File("/servers/A Paris.json")
    private val germanySlow = File("/servers/B Berlin.json")
    private val unknown = File("/servers/C Unknown.json")
    private val germanyFast = File("/servers/D Frankfurt.json")
    private val files = listOf(france, germanySlow, unknown, germanyFast)
    private val details =
        mapOf(
            france to ServerDetails("FR", "Франция"),
            germanySlow to ServerDetails("DE", "Германия"),
            germanyFast to ServerDetails("DE", "Германия"),
        )
    private val owners =
        mapOf(france.name to "travel", germanySlow.name to "work", germanyFast.name to "work")
    private val names = mapOf("travel" to "Travel", "work" to "Office")

    @Test
    fun countryGroupsKeepFirstAppearanceOrderAndLatencyOrderWithinCountry() {
        val groups =
            configGroups(
                files,
                owners,
                names,
                details,
                grouping = ServerGrouping.COUNTRY,
                sort = "latency",
                results =
                    mapOf(
                        germanyFast.absolutePath to ServerCheckResult(5),
                        france.absolutePath to ServerCheckResult(10),
                        germanySlow.absolutePath to ServerCheckResult(40),
                    ),
            )

        assertEquals(listOf("Германия", "Франция", "Без страны"), groups.map { it.title })
        assertEquals(listOf(germanyFast, germanySlow), groups[0].files)
        assertEquals(listOf(unknown), groups[2].files)
    }

    @Test
    fun countryGroupingKeepsSubscriptionFilterAndSearchBeforeGrouping() {
        val groups =
            configGroups(
                files,
                owners,
                names,
                details,
                grouping = ServerGrouping.COUNTRY,
                subscriptionFilter = "work",
                query = "  office ",
            )

        assertEquals(listOf("Германия"), groups.map { it.title })
        assertEquals(listOf(germanySlow, germanyFast), groups.single().files)
        assertTrue(
            configGroups(
                    files,
                    owners,
                    names,
                    details,
                    grouping = ServerGrouping.COUNTRY,
                    subscriptionFilter = "work",
                    query = "Франция",
                )
                .isEmpty()
        )
    }

    @Test
    fun collapseStateIsSeparateForEachGroupingAndSearchRevealsMatches() {
        val country =
            configGroups(
                    listOf(germanySlow),
                    mapOf(germanySlow.name to "DE"),
                    mapOf("DE" to "Office"),
                    details,
                    grouping = ServerGrouping.COUNTRY,
                )
                .single()
        val subscription =
            configGroups(
                    listOf(germanySlow),
                    mapOf(germanySlow.name to "DE"),
                    mapOf("DE" to "Office"),
                    details,
                )
                .single()
        val collapsed = listOf(country.key)

        assertTrue(country.isCollapsed(collapsed, ""))
        assertFalse(subscription.isCollapsed(collapsed, ""))
        assertFalse(country.isCollapsed(collapsed, "Berlin"))
    }

    @Test
    fun originalOrderAndManualSubscriptionFallbackRemainAvailable() {
        val groups =
            configGroups(
                files,
                owners + (unknown.name to "deleted"),
                names,
                details,
                sort = "original",
            )
        assertEquals(listOf("Travel", "Office", "Ручные серверы"), groups.map { it.title })
        assertEquals(listOf(germanySlow, germanyFast), groups[1].files)
        assertEquals(listOf(unknown), groups[2].files)

        val ungrouped =
            configGroups(
                files,
                owners,
                names,
                details,
                grouping = ServerGrouping.NONE,
                sort = "original",
            )
        assertEquals(files, ungrouped.single().files)
        assertFalse(ungrouped.single().isCollapsed(listOf(ungrouped.single().key), ""))
    }
}
