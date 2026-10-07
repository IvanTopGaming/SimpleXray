package com.simplexray.an.feature.routing.model

import com.google.gson.Gson
import com.google.gson.JsonParser

data class RoutingSettings(
    val version: Int = 1,
    val enabled: Boolean = true,
    val defaultRoute: RouteTarget = RouteTarget.PROXY,
    val bypassLan: Boolean = true,
    val domainStrategy: DomainStrategy = DomainStrategy.AS_IS,
    val rules: List<RoutingRule> = emptyList(),
    val blocks: List<RoutingBlock>? = null,
) {
    fun validate() {
        require(version in 1..2) { "Неподдерживаемая версия роутинга" }
        require(rules.size <= 64) { "Не больше 64 правил" }
        require(rules.map { it.id }.distinct().size == rules.size) {
            "Повторяющиеся идентификаторы правил"
        }
        rules.forEach { it.validate() }
        require(blocks != null || rules.none { it.serverBlockId != null }) {
            "Серверные правила требуют блоки"
        }
        blocks?.let { groups ->
            require(groups.size in 3..32) { "Нужно от 3 до 32 блоков" }
            require(groups.map { it.id }.distinct().size == groups.size) {
                "Повторяющиеся идентификаторы блоков"
            }
            groups.forEach { it.validate() }
            require(
                groups.filterNot { it.isServer }.map { it.target }.toSet() ==
                    RouteTarget.entries.toSet()
            ) {
                "Нужны три блока: Директ, Прокси и Блок"
            }
            require(groups.flatMap(RoutingBlocks::parse) == rules) {
                "Текст блоков не совпадает с сохранёнными правилами"
            }
        }
    }

    fun encode(): String {
        validate()
        return Gson()
            .toJson(copy(version = if (blocks.orEmpty().any { it.isServer }) 2 else 1))
            .also { require(it.length <= 2_500_000) { "Слишком большой текст правил" } }
    }

    fun upsert(rule: RoutingRule): RoutingSettings {
        rule.validate()
        val next =
            if (rules.any { it.id == rule.id }) rules.map { if (it.id == rule.id) rule else it }
            else rules + rule
        return copy(rules = next, blocks = null).also { it.validate() }
    }

    fun remove(id: String) = copy(rules = rules.filterNot { it.id == id }, blocks = null)

    fun move(id: String, direction: Int): RoutingSettings {
        val index = rules.indexOfFirst { it.id == id }
        val destination = index + direction
        if (index < 0 || direction !in listOf(-1, 1) || destination !in rules.indices) return this
        val next = rules.toMutableList()
        java.util.Collections.swap(next, index, destination)
        return copy(rules = next, blocks = null)
    }

    companion object {
        fun decode(raw: String?): RoutingSettings {
            if (raw == null) return RoutingSettings()
            try {
                require(raw.length <= 2_500_000)
                val json = JsonParser.parseString(raw).asJsonObject
                fun bool(key: String): Boolean {
                    require(
                        json[key]?.isJsonPrimitive == true && json[key].asJsonPrimitive.isBoolean
                    )
                    return json[key].asBoolean
                }
                val version = json["version"]?.toString()?.toIntOrNull()
                require(version == 1 || version == 2)
                val rules =
                    json.getAsJsonArray("rules").map { element ->
                        val rule = element.asJsonObject
                        require(
                            rule["enabled"].isJsonPrimitive &&
                                rule["enabled"].asJsonPrimitive.isBoolean
                        )
                        fun str(key: String): String {
                            require(rule[key].isJsonPrimitive && rule[key].asJsonPrimitive.isString)
                            return rule[key].asString
                        }
                        RoutingRule(
                            str("id"),
                            str("name"),
                            RuleKind.valueOf(str("kind")),
                            rule.getAsJsonArray("values").map {
                                require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                                it.asString
                            },
                            RouteTarget.valueOf(str("target")),
                            rule["enabled"].asBoolean,
                            rule["serverBlockId"]
                                ?.takeUnless { it.isJsonNull }
                                ?.let {
                                    require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                                    it.asString
                                },
                        )
                    }
                bool("enabled")
                val blocks =
                    json["blocks"]
                        ?.takeUnless { it.isJsonNull }
                        ?.asJsonArray
                        ?.map { element ->
                            val block = element.asJsonObject
                            require(
                                block["target"].isJsonPrimitive &&
                                    block["target"].asJsonPrimitive.isString
                            )
                            require(
                                block["text"].isJsonPrimitive &&
                                    block["text"].asJsonPrimitive.isString
                            )
                            RoutingBlock(
                                RouteTarget.valueOf(block["target"].asString),
                                block["text"].asString,
                                block["id"]?.let {
                                    require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                                    it.asString
                                } ?: block["target"].asString,
                                block["server"]
                                    ?.takeUnless { it.isJsonNull }
                                    ?.let {
                                        val server = it.asJsonObject
                                        fun serverString(key: String): String {
                                            require(
                                                server[key]?.isJsonPrimitive == true &&
                                                    server[key].asJsonPrimitive.isString
                                            )
                                            return server[key].asString
                                        }
                                        RoutingServerRef(
                                            fileName = serverString("fileName"),
                                            name = serverString("name"),
                                            subscriptionId =
                                                server["subscriptionId"]
                                                    ?.takeUnless { it.isJsonNull }
                                                    ?.let { serverString("subscriptionId") },
                                            fingerprint =
                                                server["fingerprint"]?.let {
                                                    serverString("fingerprint")
                                                } ?: "",
                                            matchFingerprint =
                                                server["matchFingerprint"]?.let {
                                                    require(
                                                        it.isJsonPrimitive &&
                                                            it.asJsonPrimitive.isBoolean
                                                    )
                                                    it.asBoolean
                                                } ?: false,
                                        )
                                    },
                            )
                        }
                return RoutingSettings(
                        requireNotNull(version),
                        true,
                        RouteTarget.valueOf(json["defaultRoute"].asString),
                        bool("bypassLan"),
                        DomainStrategy.valueOf(json["domainStrategy"].asString),
                        rules,
                        blocks,
                    )
                    .also { it.validate() }
            } catch (_: Exception) {
                throw IllegalArgumentException(
                    "Настройки роутинга повреждены. Восстанови их в настройках."
                )
            }
        }
    }
}
