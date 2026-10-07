package com.simplexray.an.core.config.inbound

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.core.config.dns.DnsConfigCompiler
import com.simplexray.an.core.config.ownership.OwnedConfig
import com.simplexray.an.core.network.socks.socksAuthenticationEnabled
import com.simplexray.an.feature.settings.model.InboundSettings

object InboundConfigCompiler {
    private const val UDP_BLOCK_RULE = "__sx_user_udp_block"

    fun configure(source: String, settings: InboundSettings): String {
        settings.validate()
        val root = JsonParser.parseString(source).asJsonObject
        val inbounds = root.getAsJsonArray("inbounds")
        val clients = JsonArray()
        inbounds
            .filter { it.asJsonObject["tag"]?.asString != OwnedConfig.HTTP_CLIENT_INBOUND_TAG }
            .forEach { clients.add(it) }
        val socks =
            clients
                .single { it.asJsonObject["tag"]?.asString == OwnedConfig.CLIENT_INBOUND_TAG }
                .asJsonObject
        socks.addProperty("listen", settings.effectiveListenAddress)
        socks.addProperty("port", settings.socksPort)
        val authenticated =
            socksAuthenticationEnabled(settings.socksUsername, settings.socksPassword)
        fun accounts() =
            JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("user", settings.socksUsername)
                        addProperty("pass", settings.socksPassword)
                    }
                )
            }
        socks.add(
            "settings",
            JsonObject().apply {
                addProperty("udp", true)
                addProperty("auth", if (authenticated) "password" else "noauth")
                if (authenticated) add("accounts", accounts())
            },
        )
        if (settings.httpProxyEnabled) {
            require(clients.none { it.asJsonObject["port"]?.asInt == settings.httpPort }) {
                "Порт HTTP уже занят другим локальным входом"
            }
            clients.add(
                JsonObject().apply {
                    addProperty("tag", OwnedConfig.HTTP_CLIENT_INBOUND_TAG)
                    addProperty("listen", settings.effectiveListenAddress)
                    addProperty("port", settings.httpPort)
                    addProperty("protocol", "http")
                    add(
                        "settings",
                        JsonObject().apply {
                            if (authenticated) add("accounts", accounts())
                        },
                    )
                }
            )
        }
        root.add("inbounds", clients)
        return root.toString()
    }

    fun applyTrafficPolicy(source: String, settings: InboundSettings): String {
        settings.validate()
        val root = JsonParser.parseString(source).asJsonObject
        val routing =
            root.getAsJsonObject("routing") ?: JsonObject().also { root.add("routing", it) }
        val original = routing.getAsJsonArray("rules") ?: JsonArray()
        val previousTargets =
            original
                .filter { it.asJsonObject["ruleTag"]?.asString == UDP_BLOCK_RULE }
                .mapNotNull { it.asJsonObject["outboundTag"]?.asString }
                .toSet()
        val rules = original.filter { it.asJsonObject["ruleTag"]?.asString != UDP_BLOCK_RULE }
        val outbounds = JsonArray()
        root
            .getAsJsonArray("outbounds")
            .filterNot {
                it.asJsonObject["tag"]?.asString in previousTargets &&
                    it.asJsonObject["protocol"]?.asString == "blackhole"
            }
            .forEach { outbounds.add(it) }
        root.add("outbounds", outbounds)
        if (settings.socksUdpEnabled) {
            routing.add("rules", JsonArray().apply { rules.forEach { add(it) } })
            return root.toString()
        }
        val clientTags =
            root
                .getAsJsonArray("inbounds")
                .mapNotNull { it.asJsonObject["tag"]?.asString }
                .filter {
                    it in
                        listOf(OwnedConfig.CLIENT_INBOUND_TAG, OwnedConfig.HTTP_CLIENT_INBOUND_TAG)
                }
        require(clientTags.isNotEmpty()) { "Для политики UDP нужен локальный прокси-вход" }
        val used = outbounds.mapNotNull { it.asJsonObject["tag"]?.asString }.toSet()
        var blockTag = "__sx_udp_block"
        var index = 1
        while (blockTag in used) blockTag = "__sx_udp_block_${index++}"
        outbounds.add(
            JsonObject().apply {
                addProperty("tag", blockTag)
                addProperty("protocol", "blackhole")
                add("settings", JsonObject())
            }
        )
        val block =
            JsonObject().apply {
                addProperty("type", "field")
                addProperty("ruleTag", UDP_BLOCK_RULE)
                add("inboundTag", JsonArray().apply { clientTags.forEach { add(it) } })
                addProperty("network", "udp")
                addProperty("port", "1-52,54-65535")
                addProperty("outboundTag", blockTag)
            }
        val dnsTargets =
            outbounds
                .filter { it.asJsonObject["protocol"]?.asString == "dns" }
                .mapNotNull { it.asJsonObject["tag"]?.asString }
                .toSet()
        val internalDnsTags = mutableSetOf<String>()
        root.getAsJsonObject("dns")?.let { dns ->
            dns["tag"]?.asString?.let { internalDnsTags.add(it) }
            dns.getAsJsonArray("servers")
                ?.filter { it.isJsonObject }
                ?.forEach {
                    it.asJsonObject["tag"]?.asString?.let { tag -> internalDnsTags.add(tag) }
                }
        }
        val prefixSize =
            rules
                .takeWhile {
                    val rule = it.asJsonObject
                    rule["outboundTag"]?.asString in dnsTargets ||
                        rule["ruleTag"]?.asString == DnsConfigCompiler.GUARD_RULE ||
                        rule.getAsJsonArray("inboundTag")?.let { tags ->
                            tags.size() > 0 && tags.all { tag -> tag.asString in internalDnsTags }
                        } == true
                }
                .size
        routing.add(
            "rules",
            JsonArray().apply {
                rules.take(prefixSize).forEach { add(it) }
                add(block)
                rules.drop(prefixSize).forEach { add(it) }
            },
        )
        return root.toString()
    }
}
