package com.simplexray.an.core.config.ownership

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal class ServerProfileGraph(source: JsonObject) {
    private val outbounds: List<JsonObject>
    private val tagged: Map<String, Int>
    val servers: List<Int>
    val roots: List<Int>

    init {
        val transport = source.get("transport")
        require(
            transport == null ||
                transport.isJsonNull ||
                transport.isJsonObject && transport.asJsonObject.size() == 0
        ) {
            "Общие настройки transport не поддерживаются: перенесите их в streamSettings сервера"
        }
        outbounds =
            if (source.has("outbounds")) {
                val values = source["outbounds"]
                require(values.isJsonArray) { "Поле outbounds должно быть массивом" }
                values.asJsonArray.map(::objectValue)
            } else {
                require(source.has("protocol")) { "В конфигурации нет выходящих подключений" }
                listOf(source)
            }
        val pairs = outbounds.indices.mapNotNull { index -> tag(index)?.let { it to index } }
        require(pairs.map { it.first }.distinct().size == pairs.size) {
            "В конфигурации повторяются теги выходов"
        }
        tagged = pairs.toMap()
        servers = outbounds.indices.filter { outbounds[it].string("protocol") in serverProtocols }
        val referenced = servers.flatMap { references(outbounds[it]) }.toSet()
        roots = servers.filter { tag(it) !in referenced }
    }

    fun tag(index: Int): String? = outbounds[index].string("tag")

    fun profile(
        selected: Int,
        primaryTag: String = "proxy",
        chainPrefix: String = "__sx_chain_",
        runtime: Boolean = false,
    ): JsonObject {
        val included = linkedSetOf<Int>()
        val visiting = mutableSetOf<Int>()
        fun visit(index: Int) {
            require(index !in visiting) { "В цепочке выходов обнаружен цикл" }
            if (index in included) return
            val outbound = outbounds[index]
            require(
                outbound.string("protocol") in serverProtocols ||
                    outbound.string("protocol") == "freedom"
            ) {
                "Цепочка требует неподдерживаемый выход ${outbound.string("protocol")}"
            }
            require(outbound.getAsJsonObject("settings")?.has("reverse") != true) {
                "Сервер требует неподдерживаемый обратный туннель"
            }
            visiting.add(index)
            included.add(index)
            references(outbound).forEach { reference ->
                val dependency = tagged[reference]
                require(dependency != null) { "В цепочке отсутствует выход $reference" }
                visit(dependency)
            }
            visiting.remove(index)
        }
        visit(selected)
        val names =
            included
                .mapIndexed { position, index ->
                    index to if (position == 0) primaryTag else "$chainPrefix$position"
                }
                .toMap()
        return JsonObject().apply {
            add(
                "outbounds",
                JsonArray().apply {
                    included.forEach { index ->
                        val outbound = JsonObject()
                        outbounds[index]
                            .entrySet()
                            .filter { it.key in outboundFields }
                            .forEach { (key, value) -> outbound.add(key, value.deepCopy()) }
                        outbound.addProperty("tag", names.getValue(index))
                        if (outbound.string("protocol") == "freedom") {
                            outbound.getAsJsonObject("settings")?.let { settings ->
                                if (settings.has("domainStrategy"))
                                    settings.addProperty("domainStrategy", "AsIs")
                            }
                        }
                        outbound.getAsJsonObject("proxySettings")?.let { settings ->
                            settings.string("tag")?.let {
                                settings.addProperty("tag", names.getValue(tagged.getValue(it)))
                            }
                        }
                        transformStream(outbound.get("streamSettings")) {
                            names.getValue(tagged.getValue(it))
                        }
                        if (runtime) {
                            normalizeLegacyChain(outbound)
                            LegacyTransportMigration.apply(outbound)
                            if (outbound.string("protocol") == "freedom") {
                                val settings = outbound.getAsJsonObject("settings")
                                val domainStrategy = settings?.remove("domainStrategy")
                                val targetStrategy = settings?.remove("targetStrategy")
                                if (domainStrategy != null || targetStrategy != null) {
                                    val stream =
                                        outbound["streamSettings"]
                                            ?.takeUnless { it.isJsonNull }
                                            ?.asJsonObject
                                            ?: JsonObject().also {
                                                outbound.add("streamSettings", it)
                                            }
                                    val socket =
                                        stream.getAsJsonObject("sockopt")
                                            ?: JsonObject().also { stream.add("sockopt", it) }
                                    socket.addProperty("domainStrategy", "AsIs")
                                }
                            }
                        }
                        add(outbound)
                    }
                },
            )
        }
    }

    private fun normalizeLegacyChain(outbound: JsonObject) {
        val proxy =
            outbound.remove("proxySettings")?.takeUnless { it.isJsonNull }?.asJsonObject ?: return
        val tag = proxy.string("tag") ?: return
        val stream = outbound["streamSettings"]?.takeUnless { it.isJsonNull }?.asJsonObject
        val normalized =
            if (proxy["transportLayer"]?.takeUnless { it.isJsonNull }?.asBoolean == true)
                stream ?: JsonObject()
            else
                JsonObject().apply {
                    addProperty("network", "raw")
                    if (stream?.string("security")?.lowercase() == "tls") {
                        addProperty("security", "tls")
                        val tls =
                            stream["tlsSettings"]
                                ?.takeUnless { it.isJsonNull }
                                ?.asJsonObject
                                ?.deepCopy() ?: JsonObject()
                        tls.addProperty("fingerprint", "unsafe")
                        add("tlsSettings", tls)
                    }
                }
        val socket =
            normalized.getAsJsonObject("sockopt")
                ?: JsonObject().also { normalized.add("sockopt", it) }
        socket.addProperty("dialerProxy", tag)
        outbound.add("streamSettings", normalized)
    }

    private fun references(outbound: JsonObject): List<String> {
        val references = mutableListOf<String>()
        outbound.getAsJsonObject("proxySettings")?.string("tag")?.let(references::add)
        fun visit(value: JsonElement?) {
            when {
                value?.isJsonObject == true ->
                    value.asJsonObject.entrySet().forEach { (key, child) ->
                        if (key == "dialerProxy") {
                            require(child.isJsonPrimitive && child.asJsonPrimitive.isString) {
                                "dialerProxy должен быть тегом выхода"
                            }
                            child.asString.takeIf(String::isNotBlank)?.let(references::add)
                        } else visit(child)
                    }
                value?.isJsonArray == true -> value.asJsonArray.forEach(::visit)
            }
        }
        visit(outbound.get("streamSettings"))
        return references
    }

    private fun transformStream(value: JsonElement?, rename: (String) -> String) {
        when {
            value?.isJsonObject == true ->
                value.asJsonObject.entrySet().toList().forEach { (key, child) ->
                    when {
                        key == "dialerProxy" && child.asString.isNotBlank() ->
                            value.asJsonObject.addProperty(key, rename(child.asString))
                        key == "sockopt" && child.isJsonObject -> {
                            if (child.asJsonObject.has("domainStrategy"))
                                child.asJsonObject.addProperty("domainStrategy", "AsIs")
                            transformStream(child, rename)
                        }
                        else -> transformStream(child, rename)
                    }
                }
            value?.isJsonArray == true -> value.asJsonArray.forEach { transformStream(it, rename) }
        }
    }

    companion object {
        private val serverProtocols =
            setOf(
                "vmess",
                "vless",
                "trojan",
                "shadowsocks",
                "socks",
                "http",
                "wireguard",
                "hysteria",
            )
        private val outboundFields =
            setOf("protocol", "settings", "streamSettings", "mux", "proxySettings")

        fun parse(source: String): JsonElement =
            try {
                JsonParser.parseString(source)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Конфигурация сервера не является корректным JSON",
                    e,
                )
            }

        fun objectValue(value: JsonElement): JsonObject {
            require(value.isJsonObject) { "Конфигурация сервера должна быть JSON-объектом" }
            return value.asJsonObject
        }

        fun JsonObject.string(key: String): String? {
            val value = get(key) ?: return null
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                "Поле $key должно быть строкой"
            }
            return value.asString.takeIf { it.isNotBlank() }
        }
    }
}
