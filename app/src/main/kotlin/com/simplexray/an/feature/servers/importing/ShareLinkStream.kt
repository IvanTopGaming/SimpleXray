package com.simplexray.an.feature.servers.importing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.feature.servers.importing.ShareLink.Companion.strings

internal object ShareLinkStream {
    private val kcpHeaders =
        mapOf(
            "srtp" to "srtp",
            "utp" to "utp",
            "wechat-video" to "wechat",
            "dtls" to "dtls",
            "wireguard" to "wireguard",
            "dns" to "dns",
        )

    fun build(
        options: Map<String, String>,
        address: String,
        defaultSecurity: String = "none",
    ): JsonObject {
        fun option(key: String): String? = options[key]?.takeIf(String::isNotBlank)
        val network =
            when (val type = option("type")?.lowercase() ?: "tcp") {
                "h2",
                "h3",
                "http",
                "quic" -> unsupported("Транспорт $type удалён из Xray, нужна ссылка с XHTTP")
                "splithttp" -> "xhttp"
                "mkcp" -> "kcp"
                "websocket" -> "ws"
                "tcp",
                "raw",
                "ws",
                "grpc",
                "xhttp",
                "kcp",
                "httpupgrade" -> type
                else -> unsupported("Неподдерживаемый транспорт: $type")
            }
        val security = option("security")?.lowercase() ?: defaultSecurity
        require(security in setOf("none", "tls", "reality")) {
            "Неподдерживаемая защита: $security"
        }
        val serverName = option("sni") ?: option("peer") ?: address
        return JsonObject().apply {
            addProperty("network", network)
            addProperty("security", security)
            if (security == "tls") add("tlsSettings", tls(options, serverName))
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
                        option("pqv")?.let { addProperty("mldsa65Verify", it) }
                    },
                )
            when (network) {
                "tcp",
                "raw" ->
                    if (option("headerType") == "http")
                        add(
                            "rawSettings",
                            JsonObject().apply {
                                add(
                                    "header",
                                    JsonObject().apply {
                                        addProperty("type", "http")
                                        add(
                                            "request",
                                            JsonObject().apply {
                                                add(
                                                    "path",
                                                    strings((option("path") ?: "/").split(',')),
                                                )
                                                option("host")?.let {
                                                    add(
                                                        "headers",
                                                        JsonObject().apply {
                                                            add("Host", strings(it.split(',')))
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    },
                                )
                            },
                        )
                "ws" ->
                    add(
                        "wsSettings",
                        JsonObject().apply {
                            addProperty("path", option("path") ?: "/")
                            option("host")?.let { addProperty("host", it) }
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
                "xhttp" ->
                    add(
                        "xhttpSettings",
                        JsonObject().apply {
                            addProperty("path", option("path") ?: "/")
                            option("host")?.let { addProperty("host", it) }
                            option("mode")?.let { addProperty("mode", it) }
                            option("extra")?.let {
                                val extra = runCatching { JsonParser.parseString(it) }.getOrNull()
                                require(extra?.isJsonObject == true) {
                                    "Параметр extra в XHTTP-ссылке должен быть JSON-объектом"
                                }
                                add("extra", extra)
                            }
                        },
                    )
                "kcp" -> {
                    val udp = JsonArray()
                    udp.add(
                        mask(
                            "mkcp-legacy",
                            option("seed")?.let { JsonObject().apply { addProperty("value", it) } },
                        )
                    )
                    option("headerType")
                        ?.takeIf { it != "none" }
                        ?.let { type ->
                            val header =
                                kcpHeaders[type]
                                    ?: unsupported("Неподдерживаемая маскировка mKCP: $type")
                            udp.add(
                                mask(
                                    "mkcp-legacy",
                                    JsonObject().apply {
                                        addProperty("header", header)
                                        if (header == "dns")
                                            option("host")?.let { addProperty("value", it) }
                                    },
                                )
                            )
                        }
                    add("finalmask", JsonObject().apply { add("udp", udp) })
                }
            }
        }
    }

    fun tls(options: Map<String, String>, serverName: String): JsonObject {
        fun option(key: String): String? = options[key]?.takeIf(String::isNotBlank)
        return JsonObject().apply {
            addProperty("serverName", serverName)
            option("fp")?.let { addProperty("fingerprint", it) }
            option("alpn")?.let { add("alpn", strings(it.split(','))) }
            option("pcs")?.let { addProperty("pinnedPeerCertSha256", it) }
            option("vcn")?.let { addProperty("verifyPeerCertByName", it) }
            option("ech")?.let { addProperty("echConfigList", it) }
        }
    }

    fun mask(type: String, settings: JsonObject? = null) =
        JsonObject().apply {
            addProperty("type", type)
            settings?.let { add("settings", it) }
        }

    private fun unsupported(message: String): Nothing = throw IllegalArgumentException(message)
}
