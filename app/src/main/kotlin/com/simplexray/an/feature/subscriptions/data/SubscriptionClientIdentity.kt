package com.simplexray.an.feature.subscriptions.data

import android.os.Build
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.simplexray.an.BuildConfig
import java.io.StringReader
import java.io.StringWriter
import java.text.Normalizer

internal enum class SubscriptionIdentityField(
    val key: String,
    val header: String,
    val label: String,
) {
    HWID("hwid", "x-hwid", "HWID"),
    MODEL("deviceModel", "x-device-model", "Модель устройства"),
    OS("deviceOs", "x-device-os", "Операционная система"),
    OS_VERSION("osVersion", "x-ver-os", "Версия ОС"),
    USER_AGENT("userAgent", "User-Agent", "User-Agent"),
}

internal class SubscriptionClientIdentityException :
    IllegalArgumentException(
        "Повреждены данные клиента. Открой Настройки → Подписки → Данные клиента и сбрось их."
    )

internal data class SubscriptionClientIdentity(
    private val values: Map<SubscriptionIdentityField, String> = emptyMap()
) {
    operator fun get(field: SubscriptionIdentityField): String = values[field].orEmpty()

    fun withValue(field: SubscriptionIdentityField, value: String): SubscriptionClientIdentity =
        copy(values = values + (field to value))

    fun withDefaults(defaults: SubscriptionClientIdentity): SubscriptionClientIdentity =
        SubscriptionClientIdentity(
            SubscriptionIdentityField.entries.associateWith { this[it].ifEmpty { defaults[it] } }
        )

    fun validationError(): String? {
        val hwid = this[SubscriptionIdentityField.HWID]
        if (hwid.isNotEmpty() && !Regex("[a-zA-Z0-9=-]{10,64}").matches(hwid))
            return "HWID: от 10 до 64 латинских букв, цифр, знаков = и -."
        for (field in SubscriptionIdentityField.entries) {
            val value = this[field]
            if (value.length > 512 || value.any { it !in ' '..'~' })
                return "${field.label}: до 512 ASCII-символов, без переноса строки."
        }
        return null
    }

    fun headers(): List<SubscriptionHeader> =
        SubscriptionIdentityField.entries
            .filter { it != SubscriptionIdentityField.HWID && this[it].isNotEmpty() }
            .map { SubscriptionHeader(it.header, this[it]) }

    fun encode(): String {
        require(validationError() == null)
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.beginObject()
            SubscriptionIdentityField.entries.forEach { field ->
                if (this[field].isNotEmpty()) writer.name(field.key).value(this[field])
            }
            writer.endObject()
        }
        return output.toString()
    }

    companion object {
        fun deviceDefaults(installationId: String): SubscriptionClientIdentity =
            SubscriptionClientIdentity(
                mapOf(
                    SubscriptionIdentityField.HWID to installationId,
                    SubscriptionIdentityField.MODEL to
                        deviceHeaderValue(Build.MODEL, "Android device"),
                    SubscriptionIdentityField.OS to "Android",
                    SubscriptionIdentityField.OS_VERSION to
                        deviceHeaderValue(Build.VERSION.RELEASE, "Unknown"),
                    SubscriptionIdentityField.USER_AGENT to
                        deviceHeaderValue("SimpleXray/${BuildConfig.VERSION_NAME}", "SimpleXray"),
                )
            )

        private fun deviceHeaderValue(value: String, fallback: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFKD)
                .filter { it in ' '..'~' }
                .trim()
                .take(512)
                .ifEmpty { fallback }

        fun parse(json: String): SubscriptionClientIdentity {
            try {
                require(json.length <= 8192)
                val values = mutableMapOf<SubscriptionIdentityField, String>()
                JsonReader(StringReader(json)).use { reader ->
                    reader.strictness = Strictness.STRICT
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        val field = SubscriptionIdentityField.entries.first { it.key == key }
                        require(field !in values && reader.peek() == JsonToken.STRING)
                        values[field] = reader.nextString()
                    }
                    reader.endObject()
                    require(reader.peek() == JsonToken.END_DOCUMENT)
                }
                return SubscriptionClientIdentity(values).also {
                    require(it.validationError() == null)
                }
            } catch (_: Exception) {
                throw SubscriptionClientIdentityException()
            }
        }
    }
}
