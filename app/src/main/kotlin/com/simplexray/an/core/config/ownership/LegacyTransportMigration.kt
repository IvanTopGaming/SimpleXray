package com.simplexray.an.core.config.ownership

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.math.BigInteger

internal object LegacyTransportMigration {
    fun apply(outbound: JsonObject) {
        val settings = outbound.obj("settings")
        when (outbound.text("protocol")) {
            "shadowsocks" -> {
                val target =
                    if (settings?.get("address")?.isJsonNull == false) settings
                    else settings?.array("servers")?.firstOrNull()?.asJsonObject
                target?.let {
                    require(
                        it.text("method")?.startsWith("2022-") != true ||
                            it["uot"]?.takeUnless { value -> value.isJsonNull }?.asBoolean != true
                    ) {
                        "Xray 26.9.30 больше не поддерживает Shadowsocks UDP через TCP (uot). Выбери совместимый сервер или отключи uot в исходном профиле."
                    }
                    it.remove("uot")
                    it.remove("uotVersion")
                }
            }
            "wireguard" ->
                settings?.let {
                    require(it.text("domainStrategy").isNullOrEmpty() || it.has("remoteDNS")) {
                        "Xray 26.9.30 заменил WireGuard domainStrategy: укажи remoteDNS для DNS внутри туннеля и убери domainStrategy из исходного профиля."
                    }
                    it.remove("domainStrategy")
                    it.remove("workers")
                }
        }
        outbound.obj("streamSettings")?.let { stream(it, 0) }
    }

    private fun stream(value: JsonObject, depth: Int) {
        require(depth <= 64) { "Слишком много вложенных параметров транспорта" }
        val network = (value.text("method") ?: value.text("network"))?.lowercase().orEmpty()
        if (value.text("security")?.lowercase() == "tls")
            value.obj("tlsSettings")?.let { tls ->
                require(
                    tls.text("echConfigList").isNullOrEmpty() ||
                        tls.text("echForceQuery") !in listOf("none", "half")
                ) {
                    "Xray 26.9.30 поддерживает только строгий ECH. Убери echForceQuery или выбери full в исходном профиле."
                }
                tls.remove("echForceQuery")
            }
        if (network in listOf("xhttp", "splithttp")) {
            (value.obj("xhttpSettings") ?: value.obj("splithttpSettings"))?.let { xhttp(it, depth) }
        }
        if (network in listOf("kcp", "mkcp"))
            value.obj("kcpSettings")?.let { kcp ->
                kcp.remove("congestion")
                val buffer = kcp.remove("writeBufferSize")?.takeUnless { it.isJsonNull }
                if (buffer != null && kcp["maxSendingWindow"]?.isJsonNull != false) {
                    val mib = buffer.asString.toBigIntegerOrNull()
                    require(mib != null && mib.signum() >= 0) {
                        "mKCP: некорректный старый размер буфера записи"
                    }
                    val bytes =
                        if (mib == BigInteger.ZERO) BigInteger.valueOf(524288)
                        else mib.multiply(BigInteger.valueOf(1048576))
                    require(bytes <= BigInteger("4294967295")) {
                        "mKCP: старый буфер записи превышает новое ограничение 4 ГиБ"
                    }
                    kcp.addProperty("maxSendingWindow", bytes)
                }
                kcp.remove("readBufferSize")
            }
        if (network in listOf("hysteria", "xhttp", "splithttp"))
            value.obj("finalmask")?.let { mask ->
                val legacy = mask.obj("quicParams")?.remove("udpHop")
                val udp = mask.array("udp") ?: JsonArray()
                if (udp.none { it.asJsonObject["type"]?.asString == "udphop" }) {
                    val hop = legacy?.takeIf { it.isJsonObject }?.asJsonObject
                    if (
                        hop != null &&
                            hop["ports"]?.isJsonPrimitive == true &&
                            !hop["ports"].asString.isNullOrEmpty() &&
                            hop["ports"].asString != "0"
                    ) {
                        mask.add("udp", udp)
                        val settings =
                            JsonObject().apply {
                                addProperty("mode", "intervalRemote")
                                add("remotePorts", hop["ports"])
                                hop["interval"]?.let { add("interval", it) }
                            }
                        udp.add(
                            JsonObject().apply {
                                addProperty("type", "udphop")
                                add("settings", settings)
                            }
                        )
                    }
                }
            }
    }

    private fun xhttp(value: JsonObject, depth: Int) {
        val effective = if (value.has("extra")) value.obj("extra") ?: return else value
        rename(effective, "sessionPlacement", "sessionIDPlacement")
        rename(effective, "sessionKey", "sessionIDKey")
        effective.obj("downloadSettings")?.let { stream(it, depth + 1) }
    }

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.array(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.asString

    private fun rename(value: JsonObject, old: String, modern: String) {
        value.remove(old)?.let { if (!value.has(modern)) value.add(modern, it) }
    }
}
