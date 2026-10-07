package com.simplexray.an.core.config.kernel

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.simplexray.an.core.config.routing.RoutingServerTags

internal object KernelOutboundGraph {
    fun effectiveServers(outbound: JsonObject): List<JsonObject> {
        val settings = outbound.objectOrNull("settings") ?: return emptyList()
        val key =
            when (outbound["protocol"]?.asString) {
                "hysteria" ->
                    return if (settings["address"]?.isJsonNull == false) listOf(settings)
                    else emptyList()
                "vmess",
                "vless" -> "vnext"
                "trojan",
                "shadowsocks",
                "socks",
                "http" -> "servers"
                else -> return emptyList()
            }
        if (settings["address"]?.isJsonNull == false) return listOf(settings)
        return settings.arrayOrNull(key)?.take(1)?.map { it.asJsonObject } ?: emptyList()
    }

    fun effectiveAccounts(outbound: JsonObject): List<JsonObject> {
        val settings = outbound.objectOrNull("settings") ?: return emptyList()
        val protocol = outbound["protocol"]?.asString
        if (protocol == "hysteria") return emptyList()
        val servers = effectiveServers(outbound)
        if (
            protocol == "shadowsocks" &&
                servers.firstOrNull()?.get("method")?.asString?.startsWith("2022-") == true
        )
            return emptyList()
        if (settings["address"]?.isJsonNull == false) {
            if (
                protocol in listOf("socks", "http") &&
                    settings["user"]?.takeUnless { it.isJsonNull }?.asString.isNullOrEmpty()
            )
                return emptyList()
            return servers
        }
        if (protocol in listOf("trojan", "shadowsocks")) return servers
        return servers.flatMap { server ->
            server
                .arrayOrNull("users")
                ?.take(1)
                ?.filterNot { it.isJsonNull }
                ?.map { it.asJsonObject } ?: emptyList()
        }
    }

    private fun downloadStream(stream: JsonObject): JsonObject? {
        val network =
            (stream["method"]?.takeUnless { it.isJsonNull }
                    ?: stream["network"]?.takeUnless { it.isJsonNull })
                ?.asString
                ?.lowercase()
        if (network !in listOf("xhttp", "splithttp")) return null
        val transport =
            stream.objectOrNull("xhttpSettings")
                ?: stream.objectOrNull("splithttpSettings")
                ?: return null
        val options =
            if (transport.has("extra")) transport.objectOrNull("extra") ?: return null
            else transport
        return options.objectOrNull("downloadSettings")
    }

    fun streamConfigs(outbound: JsonObject): List<JsonObject> {
        val stream = outbound.objectOrNull("streamSettings") ?: return emptyList()
        val proxy = outbound.objectOrNull("proxySettings")
        if (
            proxy != null &&
                proxy?.get("transportLayer")?.takeUnless { it.isJsonNull }?.asBoolean != true
        )
            return listOf(stream)
        return listOfNotNull(stream, downloadStream(stream))
    }

    fun activeOutbounds(owned: List<JsonObject>): List<JsonObject> {
        val tagged = owned.associateBy { it["tag"].asString }
        val visited = linkedSetOf<String>()
        fun visit(tag: String?) {
            if (tag == null || !visited.add(tag)) return
            val outbound = tagged[tag] ?: return
            val proxy = outbound.objectOrNull("proxySettings")
            val proxyTag = proxy?.get("tag")?.takeUnless { it.isJsonNull }?.asString
            if (
                proxyTag != null &&
                    proxy?.get("transportLayer")?.takeUnless { it.isJsonNull }?.asBoolean != true
            ) {
                visit(proxyTag)
                return
            }
            val stream = outbound.objectOrNull("streamSettings") ?: return
            val socket = stream.objectOrNull("sockopt")
            val dialerTag =
                proxyTag ?: socket?.get("dialerProxy")?.takeUnless { it.isJsonNull }?.asString
            visit(dialerTag)
            downloadStream(stream)?.let { download ->
                if (socket?.get("penetrate")?.takeUnless { it.isJsonNull }?.asBoolean == true)
                    visit(dialerTag)
                else
                    visit(
                        download
                            .objectOrNull("sockopt")
                            ?.get("dialerProxy")
                            ?.takeUnless { it.isJsonNull }
                            ?.asString
                    )
            }
        }
        visit("proxy")
        tagged.keys.filter(RoutingServerTags::isPrimary).forEach(::visit)
        return visited.mapNotNull(tagged::get)
    }

    fun JsonObject.objectOrNull(key: String): JsonObject? {
        val value = get(key)?.takeUnless { it.isJsonNull } ?: return null
        require(value.isJsonObject) { "Поле $key должно быть JSON-объектом" }
        return value.asJsonObject
    }

    fun JsonObject.arrayOrNull(key: String): JsonArray? {
        val value = get(key)?.takeUnless { it.isJsonNull } ?: return null
        require(value.isJsonArray) { "Поле $key должно быть массивом" }
        return value.asJsonArray
    }

    fun JsonObject.objectOrCreate(key: String): JsonObject =
        objectOrNull(key) ?: JsonObject().also { add(key, it) }

    fun strings(values: List<String>) = JsonArray().apply { values.forEach { add(it) } }
}
