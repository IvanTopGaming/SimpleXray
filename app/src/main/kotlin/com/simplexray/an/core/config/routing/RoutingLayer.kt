package com.simplexray.an.core.config.routing

import com.simplexray.an.core.geodata.GeodataFiles
import com.simplexray.an.feature.routing.model.RoutingRule
import com.simplexray.an.feature.routing.model.RuleKind

data class RoutingLayer(
    val rules: List<RoutingRule>,
    val geoipFile: String = GeodataFiles.GEOIP,
    val geositeFile: String = GeodataFiles.GEOSITE,
) {
    fun validate() {
        require(rules.size <= 64) { "Не больше 64 правил в роутинге подписки" }
        rules.forEach { it.validate() }
        require(rules.map { it.id }.distinct().size == rules.size) {
            "Повторяющиеся идентификаторы правил"
        }
        require(geoipFile == GeodataFiles.GEOIP || GeodataFiles.isSubscription(geoipFile))
        require(geositeFile == GeodataFiles.GEOSITE || GeodataFiles.isSubscription(geositeFile))
        require(GeodataFiles.isGeoip(geoipFile) && !GeodataFiles.isGeoip(geositeFile))
    }

    fun category(rule: RoutingRule, value: String): String {
        val category = RoutingRule.normalizeCategory(value, rule.kind)
        return when (rule.kind) {
            RuleKind.GEOIP ->
                if (geoipFile == GeodataFiles.GEOIP) "geoip:$category"
                else "ext:$geoipFile:$category"
            RuleKind.GEOSITE ->
                if (geositeFile == GeodataFiles.GEOSITE) "geosite:$category"
                else "ext:$geositeFile:$category"
            else -> throw IllegalArgumentException("Правило не ссылается на базу")
        }
    }

    companion object {
        fun validate(primary: List<RoutingRule>, layers: List<RoutingLayer>) {
            layers.forEach { it.validate() }
            val blocks = (listOf(primary) + layers.map { it.rules }).map { rules ->
                rules.mapNotNull { it.serverBlockId }.toSet()
            }
            require(blocks.flatten().toSet().size == blocks.sumOf { it.size }) {
                "Серверные блоки подписки пересекаются с глобальными"
            }
        }
    }
}
