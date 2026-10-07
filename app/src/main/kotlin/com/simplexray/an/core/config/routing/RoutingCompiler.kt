package com.simplexray.an.core.config.routing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.core.config.dns.DnsConfigCompiler
import com.simplexray.an.feature.routing.model.DomainStrategy
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingRule
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.routing.model.RuleKind

object RoutingCompiler {
    fun requiredGeoFiles(settings: RoutingSettings): Set<String> {
        settings.validate()
        return settings.rules
            .filter { it.enabled }
            .mapNotNull {
                when (it.kind) {
                    RuleKind.GEOIP -> "geoip.dat"
                    RuleKind.GEOSITE -> "geosite.dat"
                    else -> null
                }
            }
            .toSet()
    }

    fun compile(source: String, settings: RoutingSettings): String {
        settings.validate()
        val root =
            try {
                JsonParser.parseString(source).asJsonObject
            } catch (_: Exception) {
                throw IllegalArgumentException("Конфигурация сервера не является JSON-объектом")
            }
        val outbounds =
            root.getAsJsonArray("outbounds")
                ?: throw IllegalArgumentException("В конфигурации нет выходящих подключений")
        val inbounds =
            root.getAsJsonArray("inbounds")
                ?: throw IllegalArgumentException("В конфигурации нет входящих подключений")
        fun tagsOf(values: JsonArray) =
            values.mapNotNull { it.asJsonObject.get("tag")?.asString?.takeIf(String::isNotBlank) }
        val inboundTags = tagsOf(inbounds)
        val outboundTags = tagsOf(outbounds)
        require(
            inboundTags.distinct().size == inboundTags.size &&
                outboundTags.distinct().size == outboundTags.size
        ) {
            "В конфигурации повторяются теги подключений"
        }
        val internalTags = mutableSetOf<String>()
        root.getAsJsonObject("dns")?.let { dns ->
            dns.get("tag")?.asString?.let { internalTags.add(it) }
            dns.getAsJsonArray("servers")
                ?.filter { it.isJsonObject }
                ?.forEach {
                    it.asJsonObject.get("tag")?.asString?.let { tag -> internalTags.add(tag) }
                }
        }
        root.getAsJsonObject("reverse")?.let { reverse ->
            for (key in listOf("bridges", "portals")) reverse.getAsJsonArray(key)?.let {
                internalTags.addAll(tagsOf(it))
            }
        }
        outbounds
            .map { it.asJsonObject }
            .filter { it.get("protocol")?.asString == "loopback" }
            .forEach {
                it.getAsJsonObject("settings")?.get("inboundTag")?.asString?.let { tag ->
                    internalTags.add(tag)
                }
            }
        val used = (inboundTags + outboundTags + internalTags).toMutableSet()
        fun unique(base: String): String {
            var candidate = base
            var index = 1
            while (!used.add(candidate)) candidate = "${base}_${index++}"
            return candidate
        }
        fun JsonObject.tag(base: String): String =
            get("tag")?.asString?.takeIf(String::isNotBlank)
                ?: unique(base).also { addProperty("tag", it) }
        fun strings(values: List<String>) = JsonArray().apply { values.forEach { add(it) } }
        val clients =
            inbounds
                .map { it.asJsonObject }
                .filter { it.get("protocol")?.asString in listOf("socks", "http") }
        require(clients.isNotEmpty()) { "Для своих правил нужен SOCKS или HTTP вход" }
        val clientTags = clients.map { it.tag("__sx_client") }
        require(clientTags.none { it in internalTags }) {
            "Тег клиентского входа совпадает со служебным трафиком. Нужны разные теги."
        }
        val active = settings.rules.filter { it.enabled }
        val needProxy =
            settings.defaultRoute == RouteTarget.PROXY ||
                active.any { it.target == RouteTarget.PROXY }
        val candidates =
            outbounds
                .map { it.asJsonObject }
                .filter {
                    it.get("protocol")?.asString !in
                        listOf(null, "freedom", "blackhole", "dns", "loopback")
                }
        val proxy =
            candidates.singleOrNull { it.get("tag")?.asString == "proxy" }
                ?: candidates.singleOrNull()
        require(!needProxy || proxy != null) {
            "Не удалось однозначно выбрать прокси-выход сервера"
        }
        val targetTags = mutableMapOf<RouteTarget, String>()
        if (proxy != null) targetTags[RouteTarget.PROXY] = proxy.tag("__sx_proxy")
        for ((target, protocol) in
            listOf(RouteTarget.DIRECT to "freedom", RouteTarget.BLOCK to "blackhole")) {
            val tag = unique(if (target == RouteTarget.DIRECT) "__sx_direct" else "__sx_block")
            targetTags[target] = tag
            outbounds.add(
                JsonObject().apply {
                    addProperty("tag", tag)
                    addProperty("protocol", protocol)
                    add("settings", JsonObject())
                    if (
                        target == RouteTarget.DIRECT &&
                            root.getAsJsonObject("dns")?.has("queryStrategy") == true
                    )
                        add(
                            "streamSettings",
                            JsonObject().apply {
                                add(
                                    "sockopt",
                                    JsonObject().apply { addProperty("domainStrategy", "ForceIP") },
                                )
                            },
                        )
                }
            )
        }
        val routing =
            root.getAsJsonObject("routing") ?: JsonObject().also { root.add("routing", it) }
        val original = routing.getAsJsonArray("rules") ?: JsonArray()
        val dnsTags =
            outbounds
                .map { it.asJsonObject }
                .filter { it.get("protocol")?.asString == "dns" }
                .mapNotNull { it.get("tag")?.asString }
                .toSet()
        val ownedDns =
            original.any {
                it.asJsonObject.get("ruleTag")?.asString == DnsConfigCompiler.GUARD_RULE
            }
        val dnsRules =
            original.filter {
                val rule = it.asJsonObject
                rule.get("outboundTag")?.asString in dnsTags ||
                    rule.get("ruleTag")?.asString == DnsConfigCompiler.GUARD_RULE ||
                    ownedDns &&
                        rule.getAsJsonArray("inboundTag")?.let { tags ->
                            tags.size() > 0 && tags.all { tag -> tag.asString in internalTags }
                        } == true
            }
        val result = JsonArray()
        dnsRules.forEach { result.add(it) }
        fun scoped(target: RouteTarget, serverBlockId: String? = null): JsonObject =
            JsonObject().apply {
                addProperty("type", "field")
                add("inboundTag", strings(clientTags))
                addProperty(
                    "outboundTag",
                    if (serverBlockId == null) targetTags.getValue(target)
                    else {
                        val tag = RoutingServerTags.outbound(serverBlockId)
                        require(tag in outboundTags) {
                            "В конфигурации отсутствует сервер блока роутинга"
                        }
                        tag
                    },
                )
            }
        if (settings.bypassLan)
            result.add(
                scoped(RouteTarget.DIRECT).apply {
                    add(
                        "ip",
                        strings(
                            listOf(
                                "10.0.0.0/8",
                                "172.16.0.0/12",
                                "192.168.0.0/16",
                                "127.0.0.0/8",
                                "169.254.0.0/16",
                                "fc00::/7",
                                "fe80::/10",
                                "::1/128",
                            )
                        ),
                    )
                }
            )
        active.forEach { rule ->
            result.add(
                scoped(rule.target, rule.serverBlockId).apply {
                    if (rule.kind == RuleKind.IP) add("ip", strings(rule.values))
                    else if (rule.kind == RuleKind.GEOIP)
                        add(
                            "ip",
                            strings(
                                rule.values.map {
                                    "geoip:" + RoutingRule.normalizeCategory(it, rule.kind)
                                }
                            ),
                        )
                    else if (rule.kind == RuleKind.GEOSITE)
                        add(
                            "domain",
                            strings(
                                rule.values.map {
                                    "geosite:" + RoutingRule.normalizeCategory(it, rule.kind)
                                }
                            ),
                        )
                    else
                        add(
                            "domain",
                            strings(
                                rule.values.map {
                                    (if (rule.kind == RuleKind.DOMAIN) "domain:" else "full:") +
                                        RoutingRule.normalizeDomain(it)
                                }
                            ),
                        )
                }
            )
        }
        result.add(scoped(settings.defaultRoute))
        original.filterNot { it in dnsRules }.forEach { result.add(it) }
        routing.add("rules", result)
        val resolveFakeDomains =
            root.has("fakedns") &&
                (settings.bypassLan ||
                    active.any { it.kind == RuleKind.IP || it.kind == RuleKind.GEOIP })
        val effectiveStrategy =
            if (resolveFakeDomains) DomainStrategy.IP_ON_DEMAND else settings.domainStrategy
        routing.addProperty("domainStrategy", effectiveStrategy.configValue)
        if (active.any { it.kind != RuleKind.IP })
            clients.forEach { client ->
                val sniffing = client.getAsJsonObject("sniffing")
                if (sniffing == null) {
                    client.add(
                        "sniffing",
                        JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("routeOnly", true)
                            add("destOverride", strings(listOf("http", "tls", "quic")))
                        },
                    )
                }
            }
        return root.toString()
    }
}
