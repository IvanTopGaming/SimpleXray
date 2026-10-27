package com.simplexray.an.feature.servers.importing

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object WireguardConfigParser {
    fun parse(content: String): DetectedConfig {
        val link = ShareLink.parse(content, "wireguard", "wg")
        val secretKey = link.user
        require(!secretKey.isNullOrBlank()) { "В ссылке нет приватного ключа WireGuard" }
        val publicKey = link.option("publickey") ?: link.option("publicKey")
        require(publicKey != null) { "В ссылке нет публичного ключа WireGuard" }
        val address = link.option("address") ?: link.option("ip")
        require(address != null) { "В ссылке нет адреса интерфейса WireGuard" }
        val host = if (':' in link.host) "[${link.host}]" else link.host
        val settings =
            JsonObject().apply {
                addProperty("secretKey", secretKey)
                add("address", ShareLink.strings(address.split(',')))
                add(
                    "peers",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("publicKey", publicKey)
                                addProperty("endpoint", "$host:${link.port(51820)}")
                                (link.option("presharedkey") ?: link.option("preSharedKey"))?.let {
                                    addProperty("preSharedKey", it)
                                }
                                link.option("keepalive")?.let {
                                    addProperty("keepAlive", number(it, "keepalive"))
                                }
                            }
                        )
                    },
                )
                link.option("mtu")?.let { addProperty("mtu", number(it, "mtu")) }
                link.option("reserved")?.let { add("reserved", reserved(it)) }
            }
        return Pair(link.title(), ShareLink.config("wireguard", settings))
    }

    private fun number(value: String, key: String): Int {
        val number = value.trim().toIntOrNull()
        require(number != null && number >= 0) { "Некорректный параметр WireGuard $key" }
        return number
    }

    private fun reserved(value: String): JsonArray {
        val bytes =
            if (',' in value) value.split(',').map { it.trim().toIntOrNull() }
            else ShareLink.base64(value)?.map { it.toInt() and 0xff }
        require(bytes != null && bytes.size == 3 && bytes.all { it != null && it in 0..255 }) {
            "Параметр reserved WireGuard должен содержать три байта"
        }
        return JsonArray().apply { bytes.forEach { add(it) } }
    }
}
