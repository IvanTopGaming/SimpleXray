package com.simplexray.an.core.config.profile

import com.google.gson.JsonElement
import com.google.gson.JsonParser

object ProfileGeodata {
    fun requiredFiles(config: String): Set<String> {
        val root = JsonParser.parseString(config).asJsonObject
        val required = mutableSetOf<String>()
        fun reference(value: String) {
            if (value.startsWith("geoip:")) required.add("geoip.dat")
            if (value.startsWith("geosite:")) required.add("geosite.dat")
            require(
                !value.startsWith("ext:", true) &&
                    !value.startsWith("ext-ip:", true) &&
                    !value.startsWith("ext-domain:", true)
            ) {
                "Внешние файлы правил не поддерживаются"
            }
        }
        fun references(value: JsonElement?) {
            when {
                value == null -> Unit
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> reference(value.asString)
                value.isJsonArray ->
                    value.asJsonArray.forEach {
                        if (it.isJsonPrimitive && it.asJsonPrimitive.isString)
                            reference(it.asString)
                    }
            }
        }
        root.getAsJsonObject("routing")?.getAsJsonArray("rules")?.forEach { rule ->
            listOf("domain", "ip", "source").forEach { references(rule.asJsonObject[it]) }
        }
        root.getAsJsonObject("dns")?.let { dns ->
            dns.getAsJsonObject("hosts")?.keySet()?.forEach(::reference)
            dns.getAsJsonArray("servers")
                ?.filter { it.isJsonObject }
                ?.forEach { server ->
                    listOf("domains", "expectedIPs", "expectIPs", "unexpectedIPs").forEach {
                        references(server.asJsonObject[it])
                    }
                }
        }
        return required
    }
}
