package com.simplexray.an.core.config.kernel

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.simplexray.an.core.config.kernel.KernelOutboundGraph.activeOutbounds
import com.simplexray.an.core.config.kernel.KernelOutboundGraph.arrayOrNull
import com.simplexray.an.core.config.kernel.KernelOutboundGraph.effectiveServers
import com.simplexray.an.core.config.kernel.KernelOutboundGraph.objectOrCreate
import com.simplexray.an.core.config.kernel.KernelOutboundGraph.objectOrNull
import com.simplexray.an.core.config.kernel.KernelOutboundGraph.streamConfigs
import com.simplexray.an.core.config.kernel.KernelOutboundGraph.strings
import com.simplexray.an.feature.dns.model.DnsSettings

internal object KernelBootstrapDns {
    fun apply(root: JsonObject, owned: List<JsonObject>) {
        val domains = linkedSetOf<String>()
        fun endpoint(value: String) {
            val host = value.trim().removePrefix("[").substringBefore(']').trimEnd('.')
            if (host.isNotBlank() && !DnsSettings.isLiteralIp(host)) domains.add(host.lowercase())
        }
        fun endpointWithPort(value: String) {
            endpoint(
                if (value.startsWith("[")) value.substringBefore(']') + "]"
                else value.substringBeforeLast(':')
            )
        }
        activeOutbounds(owned).forEach { outbound ->
            effectiveServers(outbound).forEach { server ->
                server["address"]?.takeUnless { it.isJsonNull }?.asString?.let(::endpoint)
            }
            outbound.objectOrNull("settings")?.let { config ->
                if (outbound["protocol"]?.asString == "wireguard")
                    config.arrayOrNull("peers")?.forEach { peer ->
                        peer.asJsonObject["endpoint"]
                            ?.takeUnless { it.isJsonNull }
                            ?.asString
                            ?.let(::endpointWithPort)
                    }
                if (outbound["protocol"]?.asString == "freedom")
                    config["redirect"]
                        ?.takeUnless { it.isJsonNull }
                        ?.asString
                        ?.let(::endpointWithPort)
            }
            streamConfigs(outbound).drop(1).forEach { stream ->
                stream["address"]?.takeUnless { it.isJsonNull }?.asString?.let(::endpoint)
            }
        }
        if (domains.isEmpty()) return
        val dns =
            root.objectOrNull("dns")
                ?: throw IllegalArgumentException(
                    "Для разрешения адреса сервера нужны настройки DNS"
                )
        val servers = dns.getAsJsonArray("servers")
        val real =
            servers
                .filter { it.isJsonObject && it.asJsonObject["address"]?.asString != "fakedns" }
                .map { it.asJsonObject }
                .filter { it["skipFallback"]?.asBoolean != true }
        require(real.isNotEmpty()) { "Для адреса сервера нужен настоящий DNS, а не только FakeDNS" }
        val outbounds = root.getAsJsonArray("outbounds")
        val used =
            (outbounds.mapNotNull { it.asJsonObject["tag"]?.asString } +
                    root.getAsJsonArray("inbounds").mapNotNull {
                        it.asJsonObject["tag"]?.asString
                    } +
                    real.mapNotNull { it["tag"]?.asString } +
                    listOfNotNull(dns["tag"]?.asString))
                .toMutableSet()
        fun unique(base: String): String {
            var tag = base
            var index = 1
            while (!used.add(tag)) tag = "${base}_${index++}"
            return tag
        }
        val dnsTag = unique("__sx_server_dns")
        val directTag = unique("__sx_server_bootstrap")
        val result = JsonArray()
        if (root.has("fakedns"))
            result.add(
                JsonObject().apply {
                    addProperty("address", "fakedns")
                    add("domains", strings(domains.map { "full:$it" }))
                    addProperty("skipFallback", true)
                }
            )
        real.forEachIndexed { index, resolver ->
            result.add(
                resolver.deepCopy().apply {
                    addProperty("tag", dnsTag)
                    addProperty("queryStrategy", "UseIP")
                    add("domains", strings(domains.map { "full:$it" }))
                    addProperty("skipFallback", true)
                    addProperty("finalQuery", index == real.lastIndex)
                }
            )
        }
        servers.forEach { result.add(it) }
        dns.add("servers", result)
        outbounds.add(
            JsonObject().apply {
                addProperty("tag", directTag)
                addProperty("protocol", "freedom")
                add("settings", JsonObject())
                add(
                    "streamSettings",
                    JsonObject().apply {
                        add(
                            "sockopt",
                            JsonObject().apply { addProperty("domainStrategy", "ForceIP") },
                        )
                    },
                )
            }
        )
        val routing = root.objectOrCreate("routing")
        val original = routing.getAsJsonArray("rules") ?: JsonArray()
        routing.add(
            "rules",
            JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("type", "field")
                        add("inboundTag", strings(listOf(dnsTag)))
                        addProperty("outboundTag", directTag)
                    }
                )
                original.forEach { add(it) }
            },
        )
    }
}
