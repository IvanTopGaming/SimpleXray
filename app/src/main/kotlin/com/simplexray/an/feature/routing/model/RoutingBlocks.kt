package com.simplexray.an.feature.routing.model

data class RoutingBlock(
    val target: RouteTarget,
    val text: String = "",
    val id: String = target.name,
    val server: RoutingServerRef? = null,
) {
    val isServer: Boolean
        get() = id != target.name

    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,60}"))) { "Некорректный идентификатор блока" }
        if (isServer) {
            require(target == RouteTarget.PROXY && id !in RouteTarget.entries.map { it.name }) {
                "Серверный блок должен использовать прокси"
            }
            server?.validate()
        } else require(server == null) { "Встроенный блок не может содержать сервер" }
    }
}

object RoutingBlocks {
    val defaultOrder = listOf(RouteTarget.DIRECT, RouteTarget.PROXY, RouteTarget.BLOCK)

    fun fromRules(rules: List<RoutingRule>): List<RoutingBlock> {
        val order =
            (rules.filter { it.enabled }.map { it.serverBlockId ?: it.target.name } +
                    rules.mapNotNull { it.serverBlockId } +
                    defaultOrder.map { it.name })
                .distinct()
        return order.map { id ->
            val target = RouteTarget.entries.firstOrNull { it.name == id } ?: RouteTarget.PROXY
            val text =
                rules
                    .filter { (it.serverBlockId ?: it.target.name) == id }
                    .joinToString("\n\n") { rule ->
                        val prefix =
                            when (rule.kind) {
                                RuleKind.DOMAIN -> "suffix"
                                RuleKind.FULL_DOMAIN -> "full"
                                RuleKind.IP -> "ip"
                                RuleKind.GEOIP -> "geoip"
                                RuleKind.GEOSITE -> "geosite"
                            }
                        val lines = rule.name.lineSequence().map { "# $it" }.toMutableList()
                        rule.values.forEach { value ->
                            val line = "$prefix:$value"
                            if (rule.enabled) lines.add(line)
                            else lines.addAll(line.lineSequence().map { "# $it" })
                        }
                        lines.joinToString("\n")
                    }
            RoutingBlock(target, text, id)
        }
    }

    fun parse(block: RoutingBlock): List<RoutingRule> {
        block.validate()
        require(block.text.length <= 2_000_000) {
            "Строка 1: Текст блока превышает 2 000 000 символов"
        }
        val name =
            if (block.isServer) "Через сервер"
            else
                when (block.target) {
                    RouteTarget.DIRECT -> "Напрямую"
                    RouteTarget.PROXY -> "Через прокси"
                    RouteTarget.BLOCK -> "Блокировать"
                }
        val groups = linkedMapOf<RuleKind, MutableList<String>>()
        var activeLines = 0
        block.text.lineSequence().forEachIndexed { index, original ->
            val line = original.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            try {
                activeLines++
                require(activeLines <= 8192) { "Не больше 8192 строк с правилами в блоке" }
                val parts = line.split(':', limit = 2)
                require(parts.size == 2) { "Укажи тип и значение через двоеточие" }
                val kind =
                    when (parts[0]) {
                        "suffix",
                        "domain" -> RuleKind.DOMAIN
                        "full" -> RuleKind.FULL_DOMAIN
                        "ip" -> RuleKind.IP
                        "geoip" -> RuleKind.GEOIP
                        "geosite" -> RuleKind.GEOSITE
                        else ->
                            throw IllegalArgumentException("Неизвестный тип правила: ${parts[0]}")
                    }
                val value = parts[1].trim()
                require(value.isNotEmpty()) { "Укажи значение правила" }
                val normalized =
                    when (kind) {
                        RuleKind.DOMAIN,
                        RuleKind.FULL_DOMAIN -> RoutingRule.normalizeDomain(value)
                        RuleKind.GEOIP,
                        RuleKind.GEOSITE -> RoutingRule.normalizeCategory(value, kind)
                        RuleKind.IP -> value
                    }
                RoutingRule(
                        id = "block_${block.id}_${kind}_0",
                        name = name,
                        kind = kind,
                        values = listOf(normalized),
                        target = block.target,
                        serverBlockId = block.id.takeIf { block.isServer },
                    )
                    .validate()
                groups.getOrPut(kind) { mutableListOf() }.add(normalized)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Строка ${index + 1}: ${error.message}", error)
            }
        }
        return groups.flatMap { (kind, values) ->
            values.chunked(128).mapIndexed { index, chunk ->
                RoutingRule(
                    id = "block_${block.id}_${kind}_$index",
                    name = name,
                    kind = kind,
                    values = chunk,
                    target = block.target,
                    serverBlockId = block.id.takeIf { block.isServer },
                )
            }
        }
    }

    fun hasInterleavedTargets(rules: List<RoutingRule>): Boolean {
        val seen = mutableSetOf<String>()
        var previous: String? = null
        rules
            .filter { it.enabled }
            .forEach { rule ->
                val id = rule.serverBlockId ?: rule.target.name
                if (id != previous) {
                    if (!seen.add(id)) return true
                    previous = id
                }
            }
        return false
    }
}
