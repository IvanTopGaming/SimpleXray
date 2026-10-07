package com.simplexray.an.feature.dns.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingRule
import java.net.InetAddress
import java.net.URI

data class DnsSettings(
    val fakeIpEnabled: Boolean = true,
    val primaryDns: String = "8.8.8.8",
    val fallbackEnabled: Boolean = false,
    val fallbackDns: String = "1.1.1.1",
    val primaryBootstrap: String = "",
    val fallbackBootstrap: String = "",
    val route: RouteTarget = RouteTarget.PROXY,
    val queryStrategy: DnsQueryStrategy = DnsQueryStrategy.AUTO,
    val cacheEnabled: Boolean = true,
    val directDns: String = "77.88.8.8",
    val directBootstrap: String = "",
) {
    fun validate() {
        require(route != RouteTarget.BLOCK) { "DNS может идти напрямую или через прокси" }
        resolver(primaryDns, primaryBootstrap)
        resolver(directDns, directBootstrap)
        if (fallbackEnabled) resolver(fallbackDns, fallbackBootstrap)
        for (bootstrap in listOf(primaryBootstrap, fallbackBootstrap, directBootstrap)) {
            require(bootstrap.isEmpty() || isLiteralIp(bootstrap)) {
                "Bootstrap должен быть IP-адресом"
            }
        }
    }

    fun encode(): String {
        validate()
        return JsonObject()
            .apply {
                addProperty("fakeIpEnabled", fakeIpEnabled)
                addProperty("primaryDns", primaryDns)
                addProperty("fallbackEnabled", fallbackEnabled)
                addProperty("fallbackDns", fallbackDns)
                addProperty("primaryBootstrap", primaryBootstrap)
                addProperty("fallbackBootstrap", fallbackBootstrap)
                addProperty("route", route.name)
                addProperty("queryStrategy", queryStrategy.name)
                addProperty("cacheEnabled", cacheEnabled)
                addProperty("directDns", directDns)
                addProperty("directBootstrap", directBootstrap)
            }
            .toString()
    }

    companion object {
        fun decode(raw: String?, defaultPrimary: String = "8.8.8.8"): DnsSettings {
            val defaults = DnsSettings(primaryDns = defaultPrimary)
            if (raw == null) return defaults.also { it.validate() }
            try {
                require(raw.length <= 16384)
                val json = JsonParser.parseString(raw).asJsonObject
                fun str(key: String, default: String): String {
                    val value = json[key] ?: return default
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
                    return value.asString
                }
                fun bool(key: String, default: Boolean): Boolean {
                    val value = json[key] ?: return default
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
                    return value.asBoolean
                }
                return DnsSettings(
                        fakeIpEnabled = bool("fakeIpEnabled", defaults.fakeIpEnabled),
                        primaryDns = str("primaryDns", defaults.primaryDns),
                        fallbackEnabled = bool("fallbackEnabled", defaults.fallbackEnabled),
                        fallbackDns = str("fallbackDns", defaults.fallbackDns),
                        primaryBootstrap = str("primaryBootstrap", defaults.primaryBootstrap),
                        fallbackBootstrap = str("fallbackBootstrap", defaults.fallbackBootstrap),
                        route = RouteTarget.valueOf(str("route", defaults.route.name)),
                        queryStrategy =
                            DnsQueryStrategy.valueOf(
                                str("queryStrategy", defaults.queryStrategy.name)
                            ),
                        cacheEnabled = bool("cacheEnabled", defaults.cacheEnabled),
                        directDns = str("directDns", defaults.directDns),
                        directBootstrap = str("directBootstrap", defaults.directBootstrap),
                    )
                    .also { it.validate() }
            } catch (_: Exception) {
                throw IllegalArgumentException("Настройки DNS повреждены. Проверь их в настройках.")
            }
        }

        internal fun isLiteralIp(value: String): Boolean {
            if (':' in value)
                return value.matches(Regex("[0-9a-fA-F:.]+")) &&
                    runCatching { InetAddress.getByName(value) }.isSuccess
            val octets = value.split('.')
            return octets.size == 4 &&
                octets.all {
                    it.matches(Regex("0|[1-9][0-9]{0,2}")) && (it.toIntOrNull() ?: 256) <= 255
                }
        }

        internal fun resolver(value: String, bootstrap: String): Resolver {
            require(value.isNotBlank() && value.length <= 2048 && value == value.trim()) {
                "Укажи корректный DNS-сервер"
            }
            if (isLiteralIp(value)) return Resolver(value, 53, null)
            val uri =
                try {
                    URI(if ("://" in value) value else "udp://$value")
                } catch (_: Exception) {
                    throw IllegalArgumentException("Некорректный адрес DNS")
                }
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")
            require(
                host != null &&
                    uri.rawUserInfo == null &&
                    uri.rawFragment == null &&
                    (uri.port == -1 || uri.port in 1..65535)
            ) {
                "Некорректный адрес DNS"
            }
            return when (uri.scheme) {
                "udp" -> {
                    require(
                        isLiteralIp(host) && uri.rawPath.isNullOrEmpty() && uri.rawQuery == null
                    ) {
                        "UDP DNS: нужен IP-адрес и необязательный порт"
                    }
                    Resolver(host, if (uri.port == -1) 53 else uri.port, null)
                }
                "https" -> {
                    require(!uri.rawPath.isNullOrEmpty()) { "Для DoH укажи полный HTTPS URL" }
                    if (!isLiteralIp(host)) {
                        RoutingRule.normalizeDomain(host)
                        require(isLiteralIp(bootstrap)) {
                            "Для домена DoH укажи bootstrap IP сервера"
                        }
                    }
                    Resolver(value, 443, host.takeUnless(::isLiteralIp))
                }
                else -> throw IllegalArgumentException("DNS поддерживает IP, UDP и HTTPS")
            }
        }
    }
}
