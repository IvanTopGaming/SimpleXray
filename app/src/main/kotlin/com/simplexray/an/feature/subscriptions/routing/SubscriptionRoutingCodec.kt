package com.simplexray.an.feature.subscriptions.routing

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.simplexray.an.core.config.json.StrictJson
import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingBlocks
import com.simplexray.an.feature.routing.model.RoutingPreset
import com.simplexray.an.feature.routing.model.RoutingSettings
import com.simplexray.an.feature.routing.preset.RoutingPresetCodec
import java.util.Base64

object SubscriptionRoutingCodec {
    fun link(headers: List<String>, body: String): String? =
        headers.asSequence().flatMap { it.lineSequence() }.map { it.trim() }.firstOrNull(::isLink)
            ?: bodyLines(body).firstOrNull(::isLink)

    fun decodeLink(link: String): SubscriptionRouting {
        require(isLink(link)) { "Некорректная ссылка роутинга подписки" }
        return decode(RoutingPresetCodec.linkJson(link))
    }

    fun decode(raw: String): SubscriptionRouting {
        val element = StrictJson.read(raw, "Некорректный JSON роутинга подписки")
        require(element.isJsonObject) { "Нужен JSON-объект роутинга подписки" }
        val json = element.asJsonObject
        require(json.string("format") == RoutingPreset.FORMAT) {
            "Неизвестный формат роутинга подписки"
        }
        val version = json["version"]?.toString()
        require(version == "1" || version == "2") { "Неподдерживаемая версия роутинга подписки" }
        val routing = json["routing"]
        require(routing?.isJsonObject == true) { "В роутинге подписки нет правил" }
        val blocksJson = routing.asJsonObject["blocks"]?.takeUnless { it.isJsonNull }
        val blocks =
            if (blocksJson == null)
                RoutingBlocks.fromRules(RoutingSettings.decode(routing.toString()).rules).map {
                    SubscriptionRoutingBlock(it.target, it.text, it.isServer)
                }
            else {
                require(blocksJson.isJsonArray) { "Некорректные блоки роутинга подписки" }
                blocksJson.asJsonArray.map { block(it, version == "2") }
            }
        return SubscriptionRouting(
                blocks,
                json.optionalString("geoipUrl"),
                json.optionalString("geositeUrl"),
            )
            .also { it.validate() }
    }

    private fun block(element: JsonElement, servers: Boolean): SubscriptionRoutingBlock {
        require(element.isJsonObject) { "Некорректный блок роутинга подписки" }
        val block = element.asJsonObject
        val target =
            RouteTarget.entries.firstOrNull { it.name == block.string("target") }
                ?: throw IllegalArgumentException("Неизвестное назначение блока роутинга подписки")
        val id = block.optionalString("id")
        val server = block["server"]?.takeUnless { it.isJsonNull }
        val isServer = server != null || id != null && id != target.name
        require(!isServer || servers) { "Серверные блоки поддерживаются только в версии 2" }
        val name =
            server?.let {
                require(it.isJsonObject) { "Некорректный сервер блока роутинга подписки" }
                it.asJsonObject.optionalString("name")
            }
        return SubscriptionRoutingBlock(target, block.string("text"), isServer, name)
    }

    private fun isLink(value: String): Boolean =
        value.startsWith(RoutingPresetCodec.LINK_PREFIX, ignoreCase = true)

    private fun bodyLines(body: String): Sequence<String> {
        val text = body.trim()
        if (text.startsWith("{") || text.startsWith("[")) return emptySequence()
        val decoded =
            if (text.contains("://")) text
            else
                try {
                    val clean =
                        text.replace("-", "+").replace("_", "/").filterNot(Char::isWhitespace)
                    String(
                        Base64.getDecoder().decode(clean + "=".repeat((4 - clean.length % 4) % 4)),
                        Charsets.UTF_8,
                    )
                } catch (_: IllegalArgumentException) {
                    return emptySequence()
                }
        return decoded.lineSequence().map { it.trim() }
    }

    private fun JsonObject.string(key: String): String {
        val value = this[key]
        require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isString) {
            "Некорректное поле $key в роутинге подписки"
        }
        return value.asString
    }

    private fun JsonObject.optionalString(key: String): String? =
        this[key]?.takeUnless { it.isJsonNull }?.let { string(key) }
}
