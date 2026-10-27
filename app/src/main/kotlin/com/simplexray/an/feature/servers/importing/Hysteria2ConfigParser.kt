package com.simplexray.an.feature.servers.importing

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object Hysteria2ConfigParser {
    fun parse(content: String): DetectedConfig {
        val link = ShareLink.parse(content, "hysteria2", "hy2")
        val ports = link.ports ?: "443"
        validPorts(ports)
        val port = ports.substringBefore(',').substringBefore('-').toInt()
        val hop = link.option("mport")?.also(::validPorts) ?: ports.takeIf { it != "$port" }
        val udp = JsonArray()
        link.option("obfs")?.let { obfs ->
            require(obfs == "salamander") { "Неподдерживаемая маскировка Hysteria 2: $obfs" }
            val password = link.option("obfs-password")
            require(password != null) { "В ссылке нет пароля маскировки salamander" }
            udp.add(
                ShareLinkStream.mask(
                    "salamander",
                    JsonObject().apply { addProperty("password", password) },
                )
            )
        }
        hop?.let {
            udp.add(
                ShareLinkStream.mask(
                    "udphop",
                    JsonObject().apply {
                        addProperty("mode", "intervalRemote")
                        addProperty("remotePorts", it)
                    },
                )
            )
        }
        val options = link.query + listOfNotNull(link.option("pinSHA256")?.let { "pcs" to it })
        val stream =
            JsonObject().apply {
                addProperty("network", "hysteria")
                addProperty("security", "tls")
                add("tlsSettings", ShareLinkStream.tls(options, link.option("sni") ?: link.host))
                add(
                    "hysteriaSettings",
                    JsonObject().apply {
                        addProperty("version", 2)
                        link.user?.takeIf(String::isNotEmpty)?.let { addProperty("auth", it) }
                    },
                )
                if (!udp.isEmpty) add("finalmask", JsonObject().apply { add("udp", udp) })
            }
        val settings =
            JsonObject().apply {
                addProperty("version", 2)
                addProperty("address", link.host)
                addProperty("port", port)
            }
        return Pair(link.title(), ShareLink.config("hysteria", settings, stream))
    }

    private fun validPorts(value: String) {
        require(
            value.split(',').all { part ->
                val bounds = part.split('-').map { it.trim().toIntOrNull() }
                bounds.size in 1..2 &&
                    bounds.all { it != null && it in 1..65535 } &&
                    bounds.first()!! <= bounds.last()!!
            }
        ) {
            "Некорректные порты сервера: $value"
        }
    }
}
