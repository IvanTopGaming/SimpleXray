package com.simplexray.an.feature.servers.importing

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object TrojanConfigParser {
    fun parse(content: String): DetectedConfig {
        val link = ShareLink.parse(content, "trojan")
        val password = link.user
        require(!password.isNullOrBlank()) { "В ссылке нет пароля" }
        val settings =
            JsonObject().apply {
                add(
                    "servers",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("address", link.host)
                                addProperty("port", link.port(443))
                                addProperty("password", password)
                            }
                        )
                    },
                )
            }
        return Pair(
            link.title(),
            ShareLink.config(
                "trojan",
                settings,
                ShareLinkStream.build(link.query, link.host, "tls"),
            ),
        )
    }
}
