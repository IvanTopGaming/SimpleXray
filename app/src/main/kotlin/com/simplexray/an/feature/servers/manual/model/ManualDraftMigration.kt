package com.simplexray.an.feature.servers.manual.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.simplexray.an.core.config.ownership.LegacyTransportMigration

internal object ManualDraftMigration {
    fun apply(draft: ManualServerDraft): ManualServerDraft {
        val outbound =
            JsonObject().apply {
                addProperty("protocol", draft.protocol)
                add("settings", json(draft.settings))
                draft.stream?.let { add("streamSettings", json(it)) }
            }
        LegacyTransportMigration.apply(outbound)
        return draft.copy(
            settings = value(outbound["settings"], draft.settings),
            stream = outbound["streamSettings"]?.let { value(it, draft.stream) },
        )
    }

    private fun json(value: ManualValue): JsonElement =
        when (value.type) {
            "object" ->
                JsonObject().apply {
                    value.fields.forEach { (key, child) -> add(key, json(child)) }
                }
            "array" -> JsonArray().apply { value.items.forEach { add(json(it)) } }
            else -> JsonPrimitive(value.text)
        }

    private fun value(json: JsonElement, previous: ManualValue?): ManualValue {
        if (previous != null && json(previous) == json) return previous
        return when {
            json.isJsonObject ->
                ManualValue.objectValue(
                    json.asJsonObject.entrySet().associate { (key, child) ->
                        key to value(child, previous?.field(key))
                    }
                )
            json.isJsonArray ->
                ManualValue.arrayValue(
                    json.asJsonArray.mapIndexed { index, child ->
                        value(child, previous?.items?.getOrNull(index))
                    }
                )
            json.asJsonPrimitive.isNumber -> ManualValue("integer", json.asString)
            json.asJsonPrimitive.isBoolean -> ManualValue("boolean", json.asString)
            else -> ManualValue(text = json.asString)
        }
    }
}
