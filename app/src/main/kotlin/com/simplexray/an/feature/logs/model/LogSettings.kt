package com.simplexray.an.feature.logs.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class LogSettings(
    val level: String = "info",
    val access: Boolean = true,
    val dns: Boolean = true,
    val maskIp: Boolean = false,
    val trafficStats: Boolean = true,
) {
    fun validate() {
        require(level in levels) { "Недопустимый уровень журнала" }
    }

    fun encode(): String {
        validate()
        return JsonObject()
            .apply {
                addProperty("level", level)
                addProperty("access", access)
                addProperty("dns", dns)
                addProperty("maskIp", maskIp)
                addProperty("trafficStats", trafficStats)
            }
            .toString()
    }

    companion object {
        val levels = listOf("debug", "info", "warning", "error", "none")

        fun decode(raw: String?): LogSettings {
            if (raw == null) return LogSettings()
            try {
                require(raw.length <= 4096)
                val json = JsonParser.parseString(raw).asJsonObject
                fun bool(key: String, default: Boolean): Boolean {
                    val value = json[key] ?: return default
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
                    return value.asBoolean
                }
                val level =
                    json["level"]?.let {
                        require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                        it.asString
                    } ?: "info"
                return LogSettings(
                        level,
                        bool("access", true),
                        bool("dns", true),
                        bool("maskIp", false),
                        bool("trafficStats", true),
                    )
                    .also { it.validate() }
            } catch (_: Exception) {
                throw IllegalArgumentException(
                    "Настройки журналов повреждены. Проверь или сбрось их в настройках."
                )
            }
        }
    }
}
