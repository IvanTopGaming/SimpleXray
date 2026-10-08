package com.simplexray.an.feature.kernel.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class KernelSettings(
    val sniffingEnabled: Boolean = true,
    val sniffingRouteOnly: Boolean = true,
    val sniffingProtocols: Set<SniffProtocol> = SniffProtocol.entries.toSet(),
    val muxEnabled: Boolean = false,
    val muxConcurrency: Int = 0,
    val xudpConcurrency: Int = 0,
    val udp443: Udp443Mode = Udp443Mode.REJECT,
    val serverDomainStrategy: ServerDomainStrategy = ServerDomainStrategy.AS_IS,
    val tcpFastOpen: Boolean = true,
    val tcpKeepAliveInterval: Int = 0,
    val tcpUserTimeout: Int = 0,
    val tcpCongestion: TcpCongestion = TcpCongestion.SYSTEM,
    val handshake: Int? = null,
    val connectionIdle: Int? = null,
    val uplinkOnly: Int? = null,
    val downlinkOnly: Int? = null,
    val bufferSize: Int? = null,
) {
    fun validate() {
        fun range(value: Int?, min: Int, max: Int, label: String) {
            require(value == null || value in min..max) { "$label: допустимо от $min до $max" }
        }
        range(muxConcurrency, -1, 128, "TCP Mux")
        range(xudpConcurrency, -1, 1024, "XUDP")
        range(tcpKeepAliveInterval, 0, 3600, "TCP Keep Alive")
        range(tcpUserTimeout, 0, 600000, "TCP User Timeout")
        range(handshake, 1, 600, "Handshake")
        range(connectionIdle, 1, 86400, "Простой соединения")
        range(uplinkOnly, 0, 600, "Только отправка")
        range(downlinkOnly, 0, 600, "Только приём")
        range(bufferSize, 0, 65536, "Буфер")
    }

    fun encode(): String {
        validate()
        return JsonObject()
            .apply {
                addProperty("sniffingEnabled", sniffingEnabled)
                addProperty("sniffingRouteOnly", sniffingRouteOnly)
                add(
                    "sniffingProtocols",
                    JsonArray().apply {
                        SniffProtocol.entries
                            .filter { it in sniffingProtocols }
                            .forEach { add(it.name) }
                    },
                )
                addProperty("muxEnabled", muxEnabled)
                addProperty("muxConcurrency", muxConcurrency)
                addProperty("xudpConcurrency", xudpConcurrency)
                addProperty("udp443", udp443.name)
                addProperty("serverDomainStrategy", serverDomainStrategy.name)
                addProperty("tcpFastOpen", tcpFastOpen)
                addProperty("tcpKeepAliveInterval", tcpKeepAliveInterval)
                addProperty("tcpUserTimeout", tcpUserTimeout)
                addProperty("tcpCongestion", tcpCongestion.name)
                addProperty("handshake", handshake)
                addProperty("connectionIdle", connectionIdle)
                addProperty("uplinkOnly", uplinkOnly)
                addProperty("downlinkOnly", downlinkOnly)
                addProperty("bufferSize", bufferSize)
            }
            .toString()
    }

    companion object {
        fun decode(raw: String?): KernelSettings {
            val defaults = KernelSettings()
            if (raw == null) return defaults
            try {
                require(raw.length <= 16384)
                val json = JsonParser.parseString(raw).asJsonObject
                fun str(key: String, default: String): String {
                    val value = json[key] ?: return default
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
                    return value.asString
                }
                fun bool(key: String, default: Boolean): Boolean {
                    val value = json[key] ?: return default
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
                    return value.asBoolean
                }
                fun number(key: String, default: Int?): Int? {
                    val value = json[key] ?: return default
                    if (value.isJsonNull) {
                        require(default == null)
                        return null
                    }
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
                    require(value.asString.matches(Regex("-?(0|[1-9][0-9]*)")))
                    return value.asString.toInt()
                }
                val protocols =
                    json["sniffingProtocols"]?.let { value ->
                        require(value.isJsonArray)
                        value.asJsonArray
                            .map {
                                require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                                SniffProtocol.valueOf(it.asString)
                            }
                            .toSet()
                    } ?: defaults.sniffingProtocols
                return KernelSettings(
                        sniffingEnabled = bool("sniffingEnabled", defaults.sniffingEnabled),
                        sniffingRouteOnly = bool("sniffingRouteOnly", defaults.sniffingRouteOnly),
                        sniffingProtocols = protocols,
                        muxEnabled = bool("muxEnabled", defaults.muxEnabled),
                        muxConcurrency = number("muxConcurrency", defaults.muxConcurrency)!!,
                        xudpConcurrency = number("xudpConcurrency", defaults.xudpConcurrency)!!,
                        udp443 = Udp443Mode.valueOf(str("udp443", defaults.udp443.name)),
                        serverDomainStrategy =
                            ServerDomainStrategy.valueOf(
                                str("serverDomainStrategy", defaults.serverDomainStrategy.name)
                            ),
                        tcpFastOpen = bool("tcpFastOpen", defaults.tcpFastOpen),
                        tcpKeepAliveInterval =
                            number("tcpKeepAliveInterval", defaults.tcpKeepAliveInterval)!!,
                        tcpUserTimeout = number("tcpUserTimeout", defaults.tcpUserTimeout)!!,
                        tcpCongestion =
                            TcpCongestion.valueOf(
                                str("tcpCongestion", defaults.tcpCongestion.name)
                            ),
                        handshake = number("handshake", null),
                        connectionIdle = number("connectionIdle", null),
                        uplinkOnly = number("uplinkOnly", null),
                        downlinkOnly = number("downlinkOnly", null),
                        bufferSize = number("bufferSize", null),
                    )
                    .also { it.validate() }
            } catch (_: Exception) {
                throw IllegalArgumentException(
                    "Настройки ядра повреждены. Проверь или сбрось их в настройках."
                )
            }
        }
    }
}
