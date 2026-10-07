package com.simplexray.an.core.config.dns

import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingRule
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.routing.model.RuleKind

internal data class SplitDnsRule(
    val direct: Boolean,
    val domains: List<String>,
    val serverBlockId: String? = null,
)

internal object SplitDnsRules {
    fun from(settings: RoutingSettings): List<SplitDnsRule> {
        settings.validate()
        val groups = mutableListOf<SplitDnsRule>()
        settings.rules
            .filter { it.enabled }
            .forEach { rule ->
                val prefix =
                    when (rule.kind) {
                        RuleKind.DOMAIN -> "domain:"
                        RuleKind.FULL_DOMAIN -> "full:"
                        RuleKind.GEOSITE -> "geosite:"
                        else -> return@forEach
                    }
                val domains =
                    rule.values.map {
                        prefix +
                            if (rule.kind == RuleKind.GEOSITE)
                                RoutingRule.normalizeCategory(it, rule.kind)
                            else RoutingRule.normalizeDomain(it)
                    }
                val direct = rule.target == RouteTarget.DIRECT
                val last = groups.lastOrNull()
                if (last?.direct == direct && last.serverBlockId == rule.serverBlockId)
                    groups[groups.lastIndex] = last.copy(domains = last.domains + domains)
                else groups.add(SplitDnsRule(direct, domains, rule.serverBlockId))
            }
        return groups
    }
}
