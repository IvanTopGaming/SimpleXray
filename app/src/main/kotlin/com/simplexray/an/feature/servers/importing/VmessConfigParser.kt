package com.simplexray.an.feature.servers.importing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object VmessConfigParser {
    private val copied =
        listOf("host", "path", "sni", "alpn", "fp", "pbk", "sid", "spx", "pqv", "pcs", "vcn", "ech")

    fun parse(content: String): DetectedConfig {
        val text = content.trim()
        require(text.startsWith("vmess://", true)) { "Ожидалась ссылка vmess://" }
        val decoded = ShareLink.base64(text.substring("vmess://".length).substringBefore('#'))
        val json =
            decoded?.let {
                runCatching { JsonParser.parseString(String(it, Charsets.UTF_8)) }.getOrNull()
            }
        require(json?.isJsonObject == true) { "VMess-ссылка должна содержать JSON-объект" }
        val value = json.asJsonObject
        fun field(key: String): String? =
            value[key]
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        val address = field("add")
        require(address != null) { "В ссылке нет адреса сервера" }
        val id = field("id")
        require(id != null) { "В ссылке нет идентификатора пользователя" }
        val port = field("port")?.toIntOrNull() ?: 443
        require(port in 1..65535) { "Некорректный порт сервера" }
        val network = field("net") ?: "tcp"
        val options =
            buildMap {
                put("type", network)
                copied.forEach { key -> field(key)?.let { put(key, it) } }
                if ("sni" !in this) field("host")?.let { put("sni", it) }
                field("tls")?.let { put("security", it) }
                field("type")?.let {
                    val key =
                        if (network in setOf("grpc", "xhttp", "splithttp")) "mode" else "headerType"
                    put(key, it)
                }
                if (network == "grpc") field("host")?.let { put("authority", it) }
            }
        val settings =
            JsonObject().apply {
                add(
                    "vnext",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("address", address)
                                addProperty("port", port)
                                add(
                                    "users",
                                    JsonArray().apply {
                                        add(
                                            JsonObject().apply {
                                                addProperty("id", id)
                                                addProperty("security", field("scy") ?: "auto")
                                            }
                                        )
                                    },
                                )
                            }
                        )
                    },
                )
            }
        return Pair(
            field("ps") ?: address,
            ShareLink.config("vmess", settings, ShareLinkStream.build(options, address)),
        )
    }
}
