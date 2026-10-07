package com.simplexray.an.feature.servers.importing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import java.net.URLDecoder

object VlessConfigParser {
    fun parse(content: String): DetectedConfig {
        val uri = URI(content.trim())
        require(uri.scheme == "vless") { "Ожидалась VLESS-ссылка" }
        val address = uri.host?.removePrefix("[")?.removeSuffix("]")
        require(!address.isNullOrBlank()) { "В ссылке нет адреса сервера" }
        val id = uri.rawUserInfo?.let(::decode)
        require(!id.isNullOrBlank()) { "В ссылке нет идентификатора пользователя" }
        val port = if (uri.port == -1) 443 else uri.port
        require(port in 1..65535) { "Некорректный порт сервера" }
        val query =
            uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate {
                val parts = it.split('=', limit = 2)
                decode(parts[0]) to decode(parts.getOrElse(1) { "" })
            }
        fun option(key: String): String? = query[key]?.takeIf(String::isNotBlank)
        val network = option("type")?.let { if (it == "h2") "http" else it } ?: "tcp"
        val security = option("security") ?: "none"
        require(security in setOf("none", "tls", "reality")) {
            "Неподдерживаемая защита VLESS: $security"
        }
        val serverName = option("sni") ?: option("peer") ?: address
        val stream =
            JsonObject().apply {
                addProperty("network", network)
                addProperty("security", security)
                if (security == "tls")
                    add(
                        "tlsSettings",
                        JsonObject().apply {
                            addProperty("serverName", serverName)
                            option("fp")?.let { addProperty("fingerprint", it) }
                            option("alpn")?.let {
                                add(
                                    "alpn",
                                    JsonArray().apply {
                                        it.split(',').forEach { value -> add(value) }
                                    },
                                )
                            }
                            option("allowInsecure")?.let {
                                addProperty("allowInsecure", it == "1" || it.equals("true", true))
                            }
                        },
                    )
                if (security == "reality")
                    add(
                        "realitySettings",
                        JsonObject().apply {
                            val publicKey = option("pbk")
                            require(publicKey != null) { "В REALITY-ссылке нет публичного ключа" }
                            addProperty("serverName", serverName)
                            addProperty("fingerprint", option("fp") ?: "chrome")
                            addProperty("publicKey", publicKey)
                            addProperty("shortId", option("sid") ?: "")
                            addProperty("spiderX", option("spx") ?: "/")
                        },
                    )
                when (network) {
                    "ws" ->
                        add(
                            "wsSettings",
                            JsonObject().apply {
                                addProperty("path", option("path") ?: "/")
                                option("host")?.let {
                                    add("headers", JsonObject().apply { addProperty("Host", it) })
                                }
                            },
                        )
                    "grpc" ->
                        add(
                            "grpcSettings",
                            JsonObject().apply {
                                addProperty(
                                    "serviceName",
                                    option("serviceName") ?: option("path")?.trimStart('/') ?: "",
                                )
                                option("authority")?.let { addProperty("authority", it) }
                                option("mode")?.let { addProperty("multiMode", it == "multi") }
                            },
                        )
                }
            }
        val result =
            JsonObject().apply {
                add(
                    "outbounds",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("protocol", "vless")
                                add(
                                    "settings",
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
                                                                        addProperty(
                                                                            "encryption",
                                                                            option("encryption")
                                                                                ?: "none",
                                                                        )
                                                                        option("flow")?.let {
                                                                            addProperty("flow", it)
                                                                        }
                                                                    }
                                                                )
                                                            },
                                                        )
                                                    }
                                                )
                                            },
                                        )
                                    },
                                )
                                add("streamSettings", stream)
                            }
                        )
                    },
                )
            }
        return Pair(
            uri.rawFragment?.let(::decode)?.takeIf(String::isNotBlank) ?: address,
            result.toString(),
        )
    }

    private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
}
