package com.simplexray.an.feature.servers.importing

import com.google.gson.JsonObject

object SocksConfigParser {
    fun parse(content: String): DetectedConfig {
        val link = ShareLink.parse(content, "socks", "socks5")
        val credentials =
            link.user?.let { user ->
                if (':' in user) user
                else ShareLink.base64(user)?.toString(Charsets.UTF_8)?.takeIf { ':' in it } ?: user
            }
        val settings =
            JsonObject().apply {
                addProperty("address", link.host)
                addProperty("port", link.port(1080))
                credentials
                    ?.takeIf(String::isNotEmpty)
                    ?.let {
                        addProperty("user", it.substringBefore(':'))
                        addProperty("pass", it.substringAfter(':', ""))
                    }
            }
        return Pair(link.title(), ShareLink.config("socks", settings))
    }
}
