package com.simplexray.an.core.config.logging

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.feature.logs.model.LogSettings

object LoggingConfigCompiler {
    fun compile(config: String, settings: LogSettings): String {
        settings.validate()
        val root = JsonParser.parseString(config).asJsonObject
        root.add(
            "log",
            JsonObject().apply {
                addProperty("loglevel", settings.level)
                addProperty("access", if (settings.access) "" else "none")
                addProperty("error", "")
                addProperty("dnsLog", settings.dns)
                addProperty("maskAddress", if (settings.maskIp) "full" else "")
            },
        )
        val policy = root.getAsJsonObject("policy") ?: JsonObject()
        val system = policy.getAsJsonObject("system") ?: JsonObject()
        listOf(
                "statsInboundUplink",
                "statsInboundDownlink",
                "statsOutboundUplink",
                "statsOutboundDownlink",
            )
            .forEach {
                system.addProperty(it, settings.trafficStats)
            }
        policy.add("system", system)
        root.add("policy", policy)
        return root.toString()
    }

    fun trafficStatsEnabled(config: String, fallback: Boolean): Boolean {
        val root = JsonParser.parseString(config).asJsonObject
        val system = root.getAsJsonObject("policy")?.getAsJsonObject("system")
        fun flag(key: String): Boolean {
            val value = system?.get(key) ?: return fallback
            require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
                "Статистика трафика: $key должен быть true или false"
            }
            return value.asBoolean
        }
        val uplink = flag("statsInboundUplink")
        val downlink = flag("statsInboundDownlink")
        return uplink && downlink
    }
}
