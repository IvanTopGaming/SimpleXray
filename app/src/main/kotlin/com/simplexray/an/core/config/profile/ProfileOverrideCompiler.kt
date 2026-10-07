package com.simplexray.an.core.config.profile

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.core.config.dns.DnsConfigCompiler

internal object ProfileOverrideCompiler {
    fun apply(source: String, sections: JsonObject): String {
        val root = JsonParser.parseString(source).asJsonObject
        val clients =
            root
                .getAsJsonArray("inbounds")
                .map { it.asJsonObject }
                .filter { it["protocol"]?.asString in setOf("socks", "http") }
        val clientTags = clients.mapNotNull { it["tag"]?.asString }.toSet()
        val outbounds = root.getAsJsonArray("outbounds").map { it.asJsonObject }
        val outboundTags = outbounds.mapNotNull { it["tag"]?.asString }.toSet()
        val dnsTargets =
            outbounds
                .filter { it["protocol"]?.asString == "dns" }
                .mapNotNull { it["tag"]?.asString }
                .toSet()
        val originalDns = root.getAsJsonObject("dns")
        val internalTags = mutableSetOf<String>()
        originalDns?.get("tag")?.asString?.let(internalTags::add)
        originalDns
            ?.getAsJsonArray("servers")
            ?.filter { it.isJsonObject }
            ?.forEach { it.asJsonObject["tag"]?.asString?.let(internalTags::add) }
        val protectedRules =
            root
                .getAsJsonObject("routing")
                ?.getAsJsonArray("rules")
                ?.filter {
                    val rule = it.asJsonObject
                    rule["outboundTag"]?.asString in dnsTargets ||
                        rule["ruleTag"]?.asString == DnsConfigCompiler.GUARD_RULE ||
                        rule.getAsJsonArray("inboundTag")?.let { tags ->
                            tags.size() > 0 && tags.all { tag -> tag.asString in internalTags }
                        } == true
                }
                .orEmpty()

        sections.entrySet().forEach { (key, value) -> root.add(key, value.deepCopy()) }
        if (sections.has("dns")) {
            val dns = root.getAsJsonObject("dns")
            val tag = originalDns?.get("tag")?.asString
            require(!dns.has("tag") || dns["tag"].asString == tag) {
                "Служебный тег DNS изменять нельзя"
            }
            if (tag != null) dns.addProperty("tag", tag)
            dns.getAsJsonArray("servers")
                ?.filter { it.isJsonObject }
                ?.forEach {
                    val serverTag = it.asJsonObject["tag"]?.asString
                    require(serverTag !in clientTags && serverTag != "api") {
                        "Тег DNS-сервера совпадает со служебным входом"
                    }
                }
        }
        if (sections.has("routing")) {
            val routing = root.getAsJsonObject("routing")
            routing.remove("domainMatcher")
            val rules = routing.getAsJsonArray("rules") ?: JsonArray()
            rules.forEach {
                val rule = it.asJsonObject
                rule.remove("domainMatcher")
                require(rule["outboundTag"].asString in outboundTags) {
                    "Неизвестный outboundTag в правиле роутинга"
                }
                require(rule["ruleTag"]?.asString?.startsWith("__sx_") != true) {
                    "Префикс ruleTag __sx_ зарезервирован приложением"
                }
                rule.getAsJsonArray("inboundTag")?.let { tags ->
                    require(tags.size() > 0 && tags.all { tag -> tag.asString in clientTags }) {
                        "Правила профиля могут использовать только клиентские входы"
                    }
                } ?: rule.add("inboundTag", JsonArray().apply { clientTags.forEach(::add) })
                if (!rule.has("type")) rule.addProperty("type", "field")
            }
            routing.add(
                "rules",
                JsonArray().apply {
                    protectedRules.forEach { add(it.deepCopy()) }
                    rules.forEach { add(it) }
                },
            )
            if (root.has("fakedns") && rules.any { it.asJsonObject.has("ip") }) {
                routing.addProperty("domainStrategy", "IPOnDemand")
            }
        }
        return root.toString()
    }
}
