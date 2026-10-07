package com.simplexray.an.feature.routing.model

import java.net.IDN
import java.net.InetAddress
import java.util.Locale
import java.util.UUID

data class RoutingRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val kind: RuleKind = RuleKind.DOMAIN,
    val values: List<String> = emptyList(),
    val target: RouteTarget = RouteTarget.PROXY,
    val enabled: Boolean = true,
    val serverBlockId: String? = null,
) {
    fun validate() {
        serverBlockId?.let {
            require(
                target == RouteTarget.PROXY &&
                    it.matches(Regex("[A-Za-z0-9_-]{1,60}")) &&
                    it !in RouteTarget.entries.map { target -> target.name }
            ) {
                "Некорректный серверный блок правила"
            }
        }
        require(id.isNotBlank() && id.length <= 100) { "Некорректный идентификатор правила" }
        require(name.isNotBlank() && name.length <= 80) { "Название: от 1 до 80 символов" }
        require(values.size in 1..128) { "Укажи от 1 до 128 адресов" }
        values.forEach { value ->
            require(value.isNotBlank() && value.length <= 253 && value == value.trim()) {
                "Некорректный адрес в правиле"
            }
            when (kind) {
                RuleKind.IP -> validateIp(value)
                RuleKind.GEOIP,
                RuleKind.GEOSITE -> normalizeCategory(value, kind)
                else -> normalizeDomain(value)
            }
        }
    }

    companion object {
        fun normalizeCategory(value: String, kind: RuleKind): String {
            val pattern =
                if (kind == RuleKind.GEOSITE)
                    "[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}(@[a-zA-Z0-9][a-zA-Z0-9_-]{0,39}){0,8}"
                else "!?[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}"
            require(value.length <= 253 && value.matches(Regex(pattern))) {
                "Некорректная категория геоданных"
            }
            return value.lowercase(Locale.ROOT)
        }

        fun normalizeDomain(value: String): String {
            require(!value.contains(Regex("[\\s/:*]"))) { "Нужен домен без https://, пути и порта" }
            val ascii =
                try {
                    IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
                } catch (_: Exception) {
                    throw IllegalArgumentException("Некорректное доменное имя")
                }
            require(
                ascii.length in 1..253 &&
                    ascii.split('.').all { it.isNotEmpty() && it.length <= 63 } &&
                    !ascii.all { it.isDigit() || it == '.' }
            ) {
                "Некорректное доменное имя"
            }
            return ascii
        }

        internal fun validateIp(value: String) {
            val parts = value.split('/')
            require(parts.size in 1..2) { "Некорректная IP-сеть" }
            val address = parts[0]
            val ipv6 = ':' in address
            if (ipv6) {
                require(
                    address.matches(Regex("[0-9a-fA-F:.]+")) &&
                        runCatching { InetAddress.getByName(address) }.isSuccess
                ) {
                    "Некорректный IPv6"
                }
            } else {
                val octets = address.split('.')
                require(
                    octets.size == 4 &&
                        octets.all { part ->
                            part.matches(Regex("0|[1-9][0-9]{0,2}")) &&
                                (part.toIntOrNull() ?: 256) <= 255
                        }
                ) {
                    "Некорректный IPv4"
                }
            }
            if (parts.size == 2) {
                require(
                    parts[1].matches(Regex("0|[1-9][0-9]{0,2}")) &&
                        (parts[1].toIntOrNull() ?: -1) in 0..if (ipv6) 128 else 32
                ) {
                    "Некорректная длина IP-префикса"
                }
            }
        }
    }
}
