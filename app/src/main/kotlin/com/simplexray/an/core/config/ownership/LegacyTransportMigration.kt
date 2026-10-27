package com.simplexray.an.core.config.ownership

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.math.BigInteger

internal object LegacyTransportMigration {
    private val mkcpHeaders =
        mapOf(
            "srtp" to "srtp",
            "utp" to "utp",
            "wechat-video" to "wechat",
            "wechat" to "wechat",
            "dtls" to "dtls",
            "wireguard" to "wireguard",
            "dns" to "dns",
        )

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
        value.obj("finalmask")?.array("udp")?.forEach { mask(it) }
        if (network in listOf("kcp", "mkcp"))
            value.obj("kcpSettings")?.let { kcp ->
                kcpObfuscation(value, kcp)
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

    private fun kcpObfuscation(stream: JsonObject, kcp: JsonObject) {
        val header =
            kcp.remove("header")?.takeIf { it.isJsonObject }?.asJsonObject?.let { header ->
                header.text("type")?.takeUnless { it.isEmpty() || it == "none" }?.let { type ->
                    legacyMask(mkcpHeader(type), header.text("domain").takeIf { type == "dns" })
                }
            }
        val seed =
            kcp.remove("seed")?.takeUnless { it.isJsonNull }?.asString?.takeIf(String::isNotEmpty)
        if (header == null && seed == null) return
        val finalmask = stream.obj("finalmask") ?: JsonObject().also { stream.add("finalmask", it) }
        val udp = finalmask.array("udp") ?: JsonArray()
        if (udp.any { it.isJsonObject && it.asJsonObject.text("type") == "mkcp-legacy" }) return
        finalmask.add(
            "udp",
            JsonArray().apply {
                add(legacyMask(null, seed))
                header?.let(::add)
                addAll(udp)
            },
        )
    }

    private fun mask(element: JsonElement) {
        val mask = element.takeIf { it.isJsonObject }?.asJsonObject ?: return
        val settings = mask.obj("settings")
        when (val type = mask.text("type")) {
            "mkcp-original" -> replace(mask, legacyMask(null, null))
            "mkcp-aes128gcm" -> {
                val password = settings?.text("password")
                require(!password.isNullOrEmpty()) { "mKCP AES-128-GCM: укажи пароль маскировки" }
                replace(mask, legacyMask(null, password))
            }
            "xdns" -> settings?.let(::xdns)
            else -> {
                val header = type?.removePrefix("header-")
                if (type != header && header in mkcpHeaders)
                    replace(
                        mask,
                        legacyMask(header, settings?.text("domain").takeIf { header == "dns" }),
                    )
            }
        }
    }

    private fun replace(mask: JsonObject, modern: JsonObject) {
        mask.add("type", modern["type"])
        mask.remove("settings")
        modern["settings"]?.let { mask.add("settings", it) }
    }

    private fun mkcpHeader(type: String): String {
        val header = mkcpHeaders[type]
        require(header != null) { "Xray 26.10.21 не поддерживает заголовок mKCP $type" }
        return header
    }

    private fun legacyMask(header: String?, value: String?) =
        JsonObject().apply {
            addProperty("type", "mkcp-legacy")
            if (header != null || !value.isNullOrEmpty())
                add(
                    "settings",
                    JsonObject().apply {
                        header?.let { addProperty("header", it) }
                        value?.takeIf(String::isNotEmpty)?.let { addProperty("value", it) }
                    },
                )
        }

    private fun xdns(settings: JsonObject) {
        settings.remove("domain")?.takeUnless { it.isJsonNull }?.asString?.let { domain ->
            if (!settings.has("domains"))
                settings.add(
                    "domains",
                    JsonArray().apply {
                        add(JsonObject().apply { add("names", JsonArray().apply { add(domain) }) })
                    },
                )
        }
        settings.array("domains")?.filter { it.isJsonObject }?.forEach {
            val domain = it.asJsonObject
            domain.remove("name")?.takeUnless { name -> name.isJsonNull }?.let { name ->
                if (!domain.has("names")) domain.add("names", JsonArray().apply { add(name) })
            }
        }
        settings.array("resolvers")?.filter { it.isJsonObject }?.forEach {
            val resolver = it.asJsonObject
            if (!resolver.has("type") && !resolver.has("settings")) return@forEach
            val type = resolver.remove("type")?.takeUnless { value -> value.isJsonNull }?.asString
            val address = resolver.remove("settings")?.takeIf { value -> value.isJsonObject }
                ?.asJsonObject?.text("addr")
            if (address != null && !resolver.has("addrs"))
                resolver.add("addrs", JsonArray().apply { add("${type ?: "udp"}://$address") })
        }
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
