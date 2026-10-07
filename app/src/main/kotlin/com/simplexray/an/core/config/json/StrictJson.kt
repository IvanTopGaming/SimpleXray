package com.simplexray.an.core.config.json

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

internal object StrictJson {
    fun read(
        raw: String,
        errorMessage: String = "Некорректный JSON",
        onField: (String, JsonElement) -> Unit = { _, _ -> },
    ): JsonElement =
        try {
            JsonReader(StringReader(raw.removePrefix("\uFEFF"))).use { reader ->
                reader.strictness = Strictness.STRICT
                val element = readElement(reader, 0, onField)
                require(reader.peek() == JsonToken.END_DOCUMENT)
                element
            }
        } catch (_: Exception) {
            throw IllegalArgumentException(errorMessage)
        }

    private fun readElement(
        reader: JsonReader,
        depth: Int,
        onField: (String, JsonElement) -> Unit,
    ): JsonElement {
        require(depth <= 64)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val result = JsonObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    require(!result.has(key))
                    val value = readElement(reader, depth + 1, onField)
                    if (depth == 0) onField(key, value)
                    result.add(key, value)
                }
                reader.endObject()
                result
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                val result = JsonArray()
                while (reader.hasNext()) result.add(readElement(reader, depth + 1, onField))
                reader.endArray()
                result
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonParser.parseString(reader.nextString())
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                JsonNull.INSTANCE
            }
            else -> throw IllegalArgumentException()
        }
    }
}
