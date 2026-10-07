package com.simplexray.an.core.config.profile

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.simplexray.an.feature.routing.model.RoutingRule

internal object ProfileOverrideValidation {
    fun validate(name: String, value: JsonObject) {
        when (name) {
            "log" -> {
                keys(value, setOf("loglevel", "access", "error", "dnsLog", "maskAddress"))
                choice(value, "loglevel", setOf("debug", "info", "warning", "error", "none"))
                choice(value, "maskAddress", setOf("", "quarter", "half", "full"))
                choice(value, "access", setOf("", "none"))
                choice(value, "error", setOf("", "none"))
                boolean(value, "dnsLog")
            }
            "dns" -> dns(value)
            "routing" -> {
                keys(value, setOf("domainStrategy", "domainMatcher", "rules"))
                choice(value, "domainStrategy", setOf("AsIs", "IPIfNonMatch", "IPOnDemand"))
                choice(value, "domainMatcher", setOf("hybrid", "linear"))
                value["rules"]?.let { rules ->
                    require(rules.isJsonArray) { "routing.rules должен быть массивом" }
                    require(rules.asJsonArray.size() <= 1024) { "Слишком много правил роутинга" }
                    rules.asJsonArray.forEach { rule ->
                        require(rule.isJsonObject) { "Правило роутинга должно быть объектом" }
                        val item = rule.asJsonObject
                        keys(
                            item,
                            setOf(
                                "type",
                                "domain",
                                "ip",
                                "source",
                                "port",
                                "sourcePort",
                                "network",
                                "user",
                                "inboundTag",
                                "protocol",
                                "attrs",
                                "outboundTag",
                                "domainMatcher",
                                "ruleTag",
                            ),
                        )
                        choice(item, "type", setOf("field"))
                        require(item.has("outboundTag")) { "Укажи outboundTag в правиле" }
                        string(item, "outboundTag")
                        string(item, "ruleTag")
                        choice(item, "domainMatcher", setOf("hybrid", "linear"))
                        choice(item, "network", setOf("tcp", "udp", "tcp,udp", "udp,tcp"))
                        listOf("domain", "ip", "source", "user", "inboundTag", "protocol").forEach {
                            strings(item, it)
                        }
                        listOf("ip", "source").forEach { key ->
                            item.getAsJsonArray(key)?.forEach { ipRule(it.asString) }
                        }
                        item.getAsJsonArray("domain")?.forEach { domainRule(it.asString) }
                        listOf("port", "sourcePort").forEach { ports(item, it) }
                        item["attrs"]?.let { attrs ->
                            require(attrs.isJsonObject) { "attrs должен быть объектом" }
                            attrs.asJsonObject.entrySet().forEach {
                                require(isString(it.value)) { "Значение attrs должно быть строкой" }
                            }
                        }
                    }
                }
            }
            "policy" -> {
                keys(value, setOf("levels", "system"))
                value["system"]?.let { system ->
                    require(system.isJsonObject) { "policy.system должен быть объектом" }
                    val names =
                        setOf(
                            "statsInboundUplink",
                            "statsInboundDownlink",
                            "statsOutboundUplink",
                            "statsOutboundDownlink",
                        )
                    keys(system.asJsonObject, names)
                    names.forEach { boolean(system.asJsonObject, it) }
                }
                value["levels"]?.let { levels ->
                    require(levels.isJsonObject) { "policy.levels должен быть объектом" }
                    levels.asJsonObject.entrySet().forEach { (level, policy) ->
                        require(level.toLongOrNull() in 0L..4_294_967_295L && policy.isJsonObject) {
                            "Некорректный уровень policy"
                        }
                        val item = policy.asJsonObject
                        val counters =
                            setOf("statsUserUplink", "statsUserDownlink", "statsUserOnline")
                        val timeouts = setOf("handshake", "connIdle", "uplinkOnly", "downlinkOnly")
                        keys(item, counters + timeouts + "bufferSize")
                        counters.forEach { boolean(item, it) }
                        timeouts.forEach { integer(item, it, 0, 4_294_967_295L) }
                        integer(item, "bufferSize", -1, 65536)
                    }
                }
            }
            "stats" -> require(value.size() == 0) { "Секция stats должна быть пустым объектом" }
        }
    }

    private fun dns(value: JsonObject) {
        keys(
            value,
            setOf(
                "servers",
                "hosts",
                "clientIp",
                "tag",
                "queryStrategy",
                "disableCache",
                "serveStale",
                "serveExpiredTTL",
                "disableFallback",
                "disableFallbackIfMatch",
                "enableParallelQuery",
                "useSystemHosts",
            ),
        )
        string(value, "tag")
        literalIp(value, "clientIp")
        choice(value, "queryStrategy", setOf("UseIP", "UseIPv4", "UseIPv6"))
        listOf(
                "disableCache",
                "serveStale",
                "disableFallback",
                "disableFallbackIfMatch",
                "enableParallelQuery",
                "useSystemHosts",
            )
            .forEach { boolean(value, it) }
        integer(value, "serveExpiredTTL", 0, 4_294_967_295L)
        value["servers"]?.let { servers ->
            require(servers.isJsonArray) { "dns.servers должен быть массивом" }
            servers.asJsonArray.forEach { server ->
                require(isString(server) || server.isJsonObject) {
                    "DNS-сервер должен быть строкой или объектом"
                }
                if (isString(server)) require(server.asString.isNotBlank()) { "Адрес DNS пуст" }
                else {
                    val item = server.asJsonObject
                    keys(
                        item,
                        setOf(
                            "address",
                            "clientIp",
                            "port",
                            "skipFallback",
                            "domains",
                            "expectedIPs",
                            "expectIPs",
                            "queryStrategy",
                            "tag",
                            "timeoutMs",
                            "disableCache",
                            "serveStale",
                            "serveExpiredTTL",
                            "finalQuery",
                            "unexpectedIPs",
                        ),
                    )
                    require(item.has("address")) { "Укажи address DNS-сервера" }
                    string(item, "address")
                    string(item, "tag")
                    literalIp(item, "clientIp")
                    integer(item, "port", 1, 65535)
                    integer(item, "timeoutMs", 0, Long.MAX_VALUE)
                    integer(item, "serveExpiredTTL", 0, 4_294_967_295L)
                    choice(item, "queryStrategy", setOf("UseIP", "UseIPv4", "UseIPv6"))
                    listOf("skipFallback", "disableCache", "serveStale", "finalQuery").forEach {
                        boolean(item, it)
                    }
                    strings(item, "domains")
                    item.getAsJsonArray("domains")?.forEach { domainRule(it.asString) }
                    listOf("expectedIPs", "expectIPs", "unexpectedIPs").forEach { key ->
                        val field = item[key]
                        if (field != null) {
                            val items =
                                if (isString(field)) {
                                    val normalized = field.asString.split(',').map(::JsonPrimitive)
                                    item.add(
                                        key,
                                        JsonArray().apply { normalized.forEach { add(it) } },
                                    )
                                    normalized
                                } else {
                                    require(
                                        field.isJsonArray && field.asJsonArray.all(::isString)
                                    ) {
                                        "$key должен быть строкой или массивом строк"
                                    }
                                    field.asJsonArray.toList()
                                }
                            items.forEach { if (it.asString != "*") ipRule(it.asString) }
                        }
                    }
                }
            }
        }
        value["hosts"]?.let { hosts ->
            require(hosts.isJsonObject) { "dns.hosts должен быть объектом" }
            hosts.asJsonObject.entrySet().forEach { (name, address) ->
                domainRule(name)
                require(
                    isString(address) || address.isJsonArray && address.asJsonArray.all(::isString)
                ) {
                    "Адрес hosts должен быть строкой или массивом строк"
                }
                val addresses =
                    if (isString(address)) listOf(address.asString)
                    else address.asJsonArray.map { it.asString }
                addresses.forEach { host ->
                    if (host.contains(':') || host.matches(Regex("[0-9.]+"))) {
                        require(!host.contains('/')) { "Для hosts нужен адрес без префикса" }
                        RoutingRule.validateIp(host)
                    } else RoutingRule.normalizeDomain(host)
                }
            }
        }
    }

    private fun literalIp(value: JsonObject, key: String) {
        string(value, key)
        value[key]?.let {
            require(!it.asString.contains('/')) { "$key должен быть IP-адресом без префикса" }
            RoutingRule.validateIp(it.asString)
        }
    }

    private fun ipRule(value: String) {
        external(value)
        if (value.startsWith("geoip:")) {
            val category = value.removePrefix("geoip:").removePrefix("!")
            require(
                category.isNotBlank() &&
                    category.length <= 1024 &&
                    category.none { it.isWhitespace() || it in "/\\:" }
            ) {
                "Некорректная ссылка GeoIP"
            }
        } else RoutingRule.validateIp(value)
    }

    private fun domainRule(value: String) {
        external(value)
        require(value.isNotBlank()) { "Пустое доменное правило" }
        if (value.startsWith("geosite:")) {
            val parts = value.removePrefix("geosite:").split('@')
            require(
                parts.all { part ->
                    part.isNotBlank() &&
                        part.length <= 1024 &&
                        part.none { it.isWhitespace() || it in "/\\:" }
                }
            ) {
                "Некорректная ссылка GeoSite"
            }
        }
    }

    private fun keys(value: JsonObject, allowed: Set<String>) {
        require(value.keySet().all { it in allowed }) { "Неизвестное поле в JSON-переопределениях" }
    }

    private fun isString(value: JsonElement) =
        value.isJsonPrimitive && value.asJsonPrimitive.isString

    private fun string(value: JsonObject, key: String) {
        value[key]?.let {
            require(isString(it) && it.asString.isNotBlank()) {
                "$key должен быть непустой строкой"
            }
        }
    }

    private fun choice(value: JsonObject, key: String, allowed: Set<String>) {
        value[key]?.let {
            require(isString(it) && it.asString in allowed) { "Недопустимое значение $key" }
        }
    }

    private fun boolean(value: JsonObject, key: String) {
        value[key]?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) {
                "$key должен быть true или false"
            }
        }
    }

    private fun integer(value: JsonObject, key: String, min: Long, max: Long) {
        value[key]?.let {
            require(
                it.isJsonPrimitive &&
                    it.asJsonPrimitive.isNumber &&
                    it.asString.toLongOrNull() in min..max
            ) {
                "Некорректное числовое значение $key"
            }
        }
    }

    private fun strings(value: JsonObject, key: String) {
        value[key]?.let { array ->
            require(array.isJsonArray && array.asJsonArray.all(::isString)) {
                "$key должен быть массивом строк"
            }
            array.asJsonArray.forEach { external(it.asString) }
        }
    }

    private fun external(value: String) {
        require(
            !value.startsWith("ext:", true) &&
                !value.startsWith("ext-ip:", true) &&
                !value.startsWith("ext-domain:", true)
        ) {
            "Внешние файлы правил не поддерживаются; используй geoip: или geosite:"
        }
    }

    private fun ports(value: JsonObject, key: String) {
        value[key]?.let { field ->
            require(
                field.isJsonPrimitive &&
                    (field.asJsonPrimitive.isString || field.asJsonPrimitive.isNumber)
            ) {
                "Некорректный $key"
            }
            require(
                field.asString.split(',').all { part ->
                    val range = part.trim().split('-')
                    val first = range.firstOrNull()?.toIntOrNull()
                    val last = range.lastOrNull()?.toIntOrNull()
                    range.size in 1..2 &&
                        first != null &&
                        last != null &&
                        first in 1..65535 &&
                        last in first..65535
                }
            ) {
                "Некорректный диапазон $key"
            }
        }
    }
}
