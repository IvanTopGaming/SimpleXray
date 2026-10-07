package com.simplexray.an.feature.routing.preset

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.core.config.json.StrictJson
import com.simplexray.an.feature.routing.model.RoutingPreset
import com.simplexray.an.feature.routing.model.RoutingPreset.Companion.FORMAT
import com.simplexray.an.feature.routing.model.RoutingSettings
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object RoutingPresetCodec {
    fun encodeLink(preset: RoutingPreset): String =
        LINK_PREFIX +
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(encode(preset).toByteArray(Charsets.UTF_8))

    fun encode(preset: RoutingPreset): String {
        val (localRouting, geoipUrl, geositeUrl) = preset
        localRouting.validate()
        val routing = portable(localRouting)
        listOf(geoipUrl, geositeUrl).forEach { url ->
            require(
                url.length <= MAX_URL_CHARS &&
                    url.isNotBlank() &&
                    url == url.trim() &&
                    url.none { it.code < 32 || it.code == 127 } &&
                    url.toHttpUrlOrNull() != null
            ) {
                "Для геоданных нужен корректный HTTP или HTTPS URL"
            }
        }
        return JsonObject()
            .apply {
                addProperty("format", FORMAT)
                addProperty("version", if (routing.blocks.orEmpty().any { it.isServer }) 2 else 1)
                add("routing", JsonParser.parseString(routing.encode()))
                addProperty("geoipUrl", geoipUrl)
                addProperty("geositeUrl", geositeUrl)
            }
            .toString()
            .also(::checkSize)
    }

    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_URL_CHARS = 8192
    private const val LINK_PREFIX = "simplexray://routing/"
    private const val MAX_BASE64_CHARS = (MAX_BYTES * 4 + 2) / 3
    private const val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val magic = Regex("\"format\"\\s*:\\s*(?:\\[\\s*)?\"simplexray-routing(?:\"|$)")

    fun decode(raw: String): RoutingPreset {
        checkSize(raw)
        val element = StrictJson.read(raw, "Некорректный JSON настроек роутинга")
        require(element.isJsonObject) { "Нужен JSON-объект настроек роутинга" }
        return fromJson(element.asJsonObject)
    }

    fun detect(raw: String): RoutingPreset? {
        require(raw.length <= LINK_PREFIX.length + MAX_BASE64_CHARS) {
            "Ссылка настроек роутинга слишком большая"
        }
        val source = raw.removePrefix("\uFEFF").trim()
        val authority = LINK_PREFIX.dropLast(1)
        if (
            source.startsWith(authority, ignoreCase = true) &&
                (source.length == authority.length || source[authority.length] in "/?#:\\")
        ) {
            return decodeLink(source)
        }
        checkSize(raw)
        if (!source.startsWith("{")) return null
        var identified = false
        val element =
            try {
                StrictJson.read(source, "Некорректный JSON настроек роутинга") { key, value ->
                    if (key == "format") identified = identified || containsMagic(value)
                }
            } catch (error: IllegalArgumentException) {
                if (identified || magic.containsMatchIn(source)) throw error
                return null
            }
        if (!element.isJsonObject || !identified) return null
        return fromJson(element.asJsonObject)
    }

    fun readText(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count == -1) break
            if (count == 0) {
                val value = input.read()
                if (value == -1) break
                output.write(value)
            } else {
                output.write(buffer, 0, count)
            }
            require(output.size() <= MAX_BYTES) { "Пресет роутинга превышает 8 МиБ" }
        }
        return decodeUtf8(output.toByteArray())
    }

    private fun decodeLink(source: String): RoutingPreset {
        require(source.startsWith(LINK_PREFIX, ignoreCase = true)) {
            "Некорректная ссылка настроек роутинга"
        }
        val payload = source.substring(LINK_PREFIX.length)
        require(
            payload.isNotEmpty() &&
                payload.length <= MAX_BASE64_CHARS &&
                payload.length % 4 != 1 &&
                payload.all { it in BASE64_ALPHABET }
        ) {
            "Некорректное содержимое ссылки настроек роутинга"
        }
        val last = BASE64_ALPHABET.indexOf(payload.last())
        require(
            when (payload.length % 4) {
                2 -> last and 15 == 0
                3 -> last and 3 == 0
                else -> true
            }
        ) {
            "Некорректное содержимое ссылки настроек роутинга"
        }
        val bytes =
            try {
                Base64.getUrlDecoder().decode(payload)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Некорректное содержимое ссылки настроек роутинга")
            }
        require(bytes.size <= MAX_BYTES) { "Пресет роутинга превышает 8 МиБ" }
        return decode(decodeUtf8(bytes))
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            throw IllegalArgumentException("Пресет роутинга должен быть в UTF-8")
        }
    }

    private fun fromJson(json: JsonObject): RoutingPreset {
        fun string(key: String): String {
            val value = json[key]
            require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isString) {
                "Некорректное поле $key в настройках роутинга"
            }
            return value.asString
        }
        require(string("format") == FORMAT) { "Неизвестный формат настроек роутинга" }
        require(json["version"]?.toString() in setOf("1", "2")) {
            "Неподдерживаемая версия настроек роутинга"
        }
        require(json["routing"]?.isJsonObject == true) { "Отсутствуют правила роутинга" }
        return RoutingPreset(
                portable(RoutingSettings.decode(json["routing"].toString())),
                string("geoipUrl"),
                string("geositeUrl"),
            )
            .also { it.validate() }
    }

    private fun portable(routing: RoutingSettings): RoutingSettings =
        routing.copy(blocks = routing.blocks?.map { it.copy(server = null) })

    private fun checkSize(raw: String) {
        require(raw.length <= MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "Пресет роутинга превышает 8 МиБ"
        }
    }

    private fun containsMagic(value: JsonElement): Boolean =
        when {
            value.isJsonPrimitive -> value.asJsonPrimitive.isString && value.asString == FORMAT
            value.isJsonArray -> value.asJsonArray.any(::containsMagic)
            value.isJsonObject -> value.asJsonObject.entrySet().any { containsMagic(it.value) }
            else -> false
        }
}
