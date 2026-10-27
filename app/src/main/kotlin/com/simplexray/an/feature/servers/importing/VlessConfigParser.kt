package com.simplexray.an.feature.servers.importing

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object VlessConfigParser {
    fun parse(content: String): DetectedConfig {
        val link = ShareLink.parse(content, "vless")
        val id = link.user
        require(!id.isNullOrBlank()) { "В ссылке нет идентификатора пользователя" }
        val settings =
            JsonObject().apply {
                add(
                    "vnext",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("address", link.host)
                                addProperty("port", link.port(443))
                                add(
                                    "users",
                                    JsonArray().apply {
                                        add(
                                            JsonObject().apply {
                                                addProperty("id", id)
                                                addProperty(
                                                    "encryption",
                                                    link.option("encryption") ?: "none",
                                                )
                                                link.option("flow")?.let { addProperty("flow", it) }
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
            link.title(),
            ShareLink.config("vless", settings, ShareLinkStream.build(link.query, link.host)),
        )
    }
}
