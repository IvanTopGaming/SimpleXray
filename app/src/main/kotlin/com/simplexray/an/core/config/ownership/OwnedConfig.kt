package com.simplexray.an.core.config.ownership

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.simplexray.an.core.config.ownership.ServerProfileGraph.Companion.objectValue
import com.simplexray.an.core.config.ownership.ServerProfileGraph.Companion.parse
import com.simplexray.an.core.config.ownership.ServerProfileGraph.Companion.string
import com.simplexray.an.core.network.socks.socksAuthenticationEnabled
import com.simplexray.an.feature.servers.importing.DetectedConfig

object OwnedConfig {
    const val HTTP_CLIENT_INBOUND_TAG = "__sx_http_client"
    const val CLIENT_INBOUND_TAG = "__sx_client"

    fun build(
        source: String,
        socksHost: String,
        socksPort: Int,
        socksUsername: String = "",
        socksPassword: String = "",
        dnsServers: List<String> = emptyList(),
    ): String {
        require(socksHost.isNotBlank() && socksPort in 1..65535) {
            "Некорректный адрес локального SOCKS"
        }
        val root = profile(source, runtime = true)
        val authenticated = socksAuthenticationEnabled(socksUsername, socksPassword)
        root.add(
            "inbounds",
            JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("tag", CLIENT_INBOUND_TAG)
                        addProperty("listen", socksHost)
                        addProperty("port", socksPort)
                        addProperty("protocol", "socks")
                        add(
                            "settings",
                            JsonObject().apply {
                                addProperty("udp", true)
                                addProperty("auth", if (authenticated) "password" else "noauth")
                                if (authenticated)
                                    add(
                                        "accounts",
                                        JsonArray().apply {
                                            add(
                                                JsonObject().apply {
                                                    addProperty("user", socksUsername)
                                                    addProperty("pass", socksPassword)
                                                }
                                            )
                                        },
                                    )
                            },
                        )
                    }
                )
            },
        )
        root.add(
            "dns",
            JsonObject().apply {
                addProperty("tag", "__sx_dns")
                add("servers", strings(dnsServers.ifEmpty { listOf("localhost") }))
            },
        )
        root.add(
            "routing",
            JsonObject().apply {
                add(
                    "rules",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("type", "field")
                                add("inboundTag", strings(listOf("__sx_dns")))
                                addProperty("outboundTag", "proxy")
                            }
                        )
                    },
                )
            },
        )
        return root.toString()
    }

    fun serverProfile(source: String): String = profile(source, runtime = false).toString()

    private fun profile(source: String, runtime: Boolean): JsonObject {
        val graph = ServerProfileGraph(objectValue(parse(source)))
        val preferred = graph.roots.filter { graph.tag(it) == "proxy" }
        val selected = preferred.singleOrNull() ?: graph.roots.singleOrNull()
        require(selected != null) { "Не удалось однозначно выбрать прокси-выход сервера" }
        return graph.profile(selected, runtime = runtime)
    }

    fun importProfiles(source: String): List<DetectedConfig> {
        val parsed = parse(source)
        val objects = if (parsed.isJsonArray) parsed.asJsonArray.toList() else listOf(parsed)
        return objects.flatMapIndexed { index, value ->
            val input = objectValue(value)
            val graph = ServerProfileGraph(input)
            val roots = graph.roots
            require(roots.isNotEmpty()) { "В конфигурации нет независимого прокси-выхода" }
            roots.mapIndexed { serverIndex, root ->
                val title =
                    if (roots.size == 1) {
                        input.string("remarks")
                            ?: input.string("name")
                            ?: input.string("ps")
                            ?: graph.tag(root)
                    } else graph.tag(root)
                Pair(
                    title ?: "server_${index + 1}_${serverIndex + 1}",
                    graph.profile(root).toString(),
                )
            }
        }
    }

    private fun strings(values: List<String>) = JsonArray().apply { values.forEach { add(it) } }
}
