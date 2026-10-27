package com.simplexray.an.feature.servers.importing

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URLDecoder
import java.util.Base64

internal class ShareLink(
    val user: String?,
    val host: String,
    val ports: String?,
    val query: Map<String, String>,
    val name: String?,
) {
    fun option(key: String): String? = query[key]?.takeIf(String::isNotBlank)

    fun port(default: Int? = null): Int {
        val value = ports ?: default?.toString()
        require(value != null) { "В ссылке нет порта сервера" }
        val port = value.toIntOrNull()
        require(port != null && port in 1..65535) { "Некорректный порт сервера" }
        return port
    }

    fun title() = name ?: host

    companion object {
        fun parse(content: String, vararg schemes: String): ShareLink {
            val text = content.trim()
            val scheme = text.substringBefore("://", "")
            require(schemes.any { it.equals(scheme, true) }) {
                "Ожидалась ссылка ${schemes.first()}://"
            }
            val rest = text.substringAfter("://")
            val body = rest.substringBefore('#').substringBefore('?')
            val at = body.lastIndexOf('@')
            return create(
                if (at >= 0) body.substring(0, at) else null,
                body.substring(at + 1).substringBefore('/'),
                rest.substringBefore('#').substringAfter('?', ""),
                rest.substringAfter('#', ""),
            )
        }

        fun create(
            rawUser: String?,
            endpoint: String,
            rawQuery: String,
            rawName: String,
        ): ShareLink {
            val host: String
            val ports: String?
            if (endpoint.startsWith('[')) {
                host = endpoint.substringAfter('[').substringBefore(']')
                ports = endpoint.substringAfter(']', "").substringAfter(':', "")
            } else {
                host = endpoint.substringBefore(':')
                ports = endpoint.substringAfter(':', "")
            }
            require(host.isNotBlank()) { "В ссылке нет адреса сервера" }
            return ShareLink(
                rawUser?.let(::decode),
                host,
                ports.takeIf(String::isNotEmpty),
                rawQuery.split('&').filter(String::isNotBlank).associate {
                    val parts = it.split('=', limit = 2)
                    decode(parts[0]) to decode(parts.getOrElse(1) { "" })
                },
                decode(rawName).takeIf(String::isNotBlank),
            )
        }

        fun decode(value: String): String =
            runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }
                .getOrDefault(value)

        fun base64(value: String): ByteArray? {
            val clean = value.trim().replace('-', '+').replace('_', '/').trimEnd('=')
            return runCatching {
                    Base64.getDecoder().decode(clean + "=".repeat((4 - clean.length % 4) % 4))
                }
                .getOrNull()
        }

        fun strings(values: List<String>) =
            JsonArray().apply { values.map(String::trim).filter(String::isNotEmpty).forEach(::add) }

        fun config(protocol: String, settings: JsonObject, stream: JsonObject? = null): String =
            JsonObject()
                .apply {
                    add(
                        "outbounds",
                        JsonArray().apply {
                            add(
                                JsonObject().apply {
                                    addProperty("protocol", protocol)
                                    add("settings", settings)
                                    stream?.let { add("streamSettings", it) }
                                }
                            )
                        },
                    )
                }
                .toString()
    }
}

internal class ShareLinkConverter(
    private val parse: (String) -> DetectedConfig,
    private vararg val schemes: String,
) : ConfigFormatConverter {
    override fun detect(content: String): Boolean =
        schemes.any { content.startsWith("$it://", true) }

    override fun convert(context: Context, content: String): Result<DetectedConfig> = runCatching {
        parse(content)
    }
}
