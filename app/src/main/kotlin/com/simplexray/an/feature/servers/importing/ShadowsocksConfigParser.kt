package com.simplexray.an.feature.servers.importing

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object ShadowsocksConfigParser {
    fun parse(content: String): DetectedConfig {
        val text = content.trim()
        require(text.startsWith("ss://", true)) { "Ожидалась ссылка ss://" }
        val rest = text.substring("ss://".length)
        val body = rest.substringBefore('#').substringBefore('?')
        val link: ShareLink
        val credentials: String
        if ('@' in body) {
            link = ShareLink.parse(text, "ss")
            val user = link.user.orEmpty()
            credentials =
                if (':' in user) user
                else ShareLink.base64(user)?.toString(Charsets.UTF_8).orEmpty()
        } else {
            val decoded = ShareLink.base64(body.trimEnd('/'))?.toString(Charsets.UTF_8).orEmpty()
            require('@' in decoded) { "Некорректная Shadowsocks-ссылка" }
            link =
                ShareLink.create(
                    null,
                    decoded.substringAfterLast('@'),
                    rest.substringBefore('#').substringAfter('?', ""),
                    rest.substringAfter('#', ""),
                )
            credentials = decoded.substringBeforeLast('@')
        }
        val method = credentials.substringBefore(':', "")
        val password = credentials.substringAfter(':', "")
        require(method.isNotBlank() && password.isNotEmpty()) {
            "В ссылке нет метода шифрования или пароля"
        }
        link.option("plugin")?.takeUnless { it == "none" }?.let {
            throw IllegalArgumentException(
                "Плагин Shadowsocks ${it.substringBefore(';')} не поддерживается ядром Xray"
            )
        }
        val settings =
            JsonObject().apply {
                add(
                    "servers",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("address", link.host)
                                addProperty("port", link.port())
                                addProperty("method", method)
                                addProperty("password", password)
                            }
                        )
                    },
                )
            }
        return Pair(
            link.title(),
            ShareLink.config("shadowsocks", settings, ShareLinkStream.build(link.query, link.host)),
        )
    }
}
