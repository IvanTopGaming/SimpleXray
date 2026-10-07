package com.simplexray.an.feature.servers.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

data class ServerDetails(
    val code: String = "—",
    val country: String? = null,
    val protocol: String? = null,
)

private val countryFlag = Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}")
private val countryCodes = Locale.getISOCountries().toSet()

fun serverDetails(filename: String, content: String?): ServerDetails {
    val flag = countryFlag.find(filename)?.value
    val code =
        flag
            ?.let {
                val first = Character.codePointAt(it, 0) - 0x1F1E6 + 'A'.code
                val second = Character.codePointAt(it, 2) - 0x1F1E6 + 'A'.code
                "${first.toChar()}${second.toChar()}"
            }
            ?.takeIf { it in countryCodes }
    val protocol = runCatching {
        val root = content?.let(JsonParser::parseString)
        val outbounds = root?.takeIf { it.isJsonObject }?.asJsonObject?.get("outbounds")
        val entries =
            outbounds
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull {
                    it.takeIf { item -> item.isJsonObject }?.asJsonObject
                }
                .orEmpty()
        (entries.firstOrNull { it.stringValue("tag") == "proxy" }
                ?: entries.firstOrNull {
                    it.stringValue("protocol") !in listOf("freedom", "blackhole", "dns")
                }
                ?: entries.firstOrNull())
            ?.stringValue("protocol")
            ?.takeIf(String::isNotBlank)
            ?.uppercase(Locale.ROOT)
    }
        .getOrNull()
    return ServerDetails(
        code ?: "—",
        code?.let { Locale("", it).getDisplayCountry(Locale("ru")) },
        protocol,
    )
}

private fun JsonObject.stringValue(name: String): String {
    val value = get(name) ?: return ""
    return when {
        value.isJsonNull -> ""
        value.isJsonPrimitive -> value.asString
        else -> value.toString()
    }
}
