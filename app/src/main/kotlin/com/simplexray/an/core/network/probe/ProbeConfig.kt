package com.simplexray.an.core.network.probe

import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal fun probeOutboundIndex(config: String): Int {
    val outbounds =
        probeRoot(config).getAsJsonArray("outbounds")
            ?: throw IllegalArgumentException("В конфигурации нет сервера")
    val entries = outbounds.map { it.asJsonObject }
    val tagged = entries.indices.filter { entries[it].get("tag")?.asString == "proxy" }
    require(tagged.size <= 1) { "В конфигурации несколько серверов с тегом proxy" }
    if (tagged.isNotEmpty()) return tagged.single()
    val supported =
        entries.indices.filter {
            entries[it].get("protocol")?.asString in
                setOf("vmess", "vless", "trojan", "shadowsocks", "socks", "http")
        }
    require(supported.size == 1) { "Не удалось однозначно выбрать сервер для проверки" }
    return supported.single()
}

internal fun probeTcpEndpoint(config: String): ProbeEndpoint {
    try {
        val outbound =
            probeRoot(config).getAsJsonArray("outbounds")[probeOutboundIndex(config)].asJsonObject
        val key =
            when (outbound.get("protocol")?.asString) {
                "vmess",
                "vless" -> "vnext"
                "trojan",
                "shadowsocks",
                "socks",
                "http" -> "servers"
                else ->
                    throw IllegalArgumentException("TCP-проверка не поддерживает этот тип сервера")
            }
        val endpoints = outbound.getAsJsonObject("settings")?.getAsJsonArray(key)
        require(endpoints?.size() == 1) { "Для TCP-проверки нужен один адрес сервера" }
        val endpoint = endpoints!![0].asJsonObject
        val host = endpoint.get("address")?.asString?.trim().orEmpty()
        val port = endpoint.get("port")?.asString?.toIntOrNull()
        require(
            host.isNotEmpty() &&
                host.none { it.isWhitespace() || it == '/' } &&
                port != null &&
                port in 1..65535
        ) {
            "Некорректный адрес или порт сервера"
        }
        return ProbeEndpoint(host.removeSurrounding("[", "]"), port)
    } catch (invalid: IllegalArgumentException) {
        throw invalid
    } catch (invalid: Exception) {
        throw IllegalArgumentException("Некорректная конфигурация сервера", invalid)
    }
}

private fun probeRoot(config: String): JsonObject =
    try {
        JsonParser.parseString(config).asJsonObject
    } catch (invalid: Exception) {
        throw IllegalArgumentException("Некорректная конфигурация сервера", invalid)
    }
