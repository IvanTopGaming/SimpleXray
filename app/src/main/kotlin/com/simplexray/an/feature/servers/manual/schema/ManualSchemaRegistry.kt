package com.simplexray.an.feature.servers.manual.schema

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.simplexray.an.feature.servers.manual.model.ManualServerDraft
import com.simplexray.an.feature.servers.manual.model.ManualValue

class ManualSchemaRegistry
private constructor(
    val protocols: List<ManualProtocol>,
    private val schemas: Map<String, JsonObject>,
) {
    fun protocol(id: String): ManualProtocol = protocols.single { it.id == id }

    fun label(id: String): String = schemas.getValue(id)["label"].asString

    fun fields(id: String): List<ManualField> =
        schemas.getValue(id).getAsJsonObject("fields").entrySet().map { (key, value) ->
            ManualField(key, value.asJsonObject)
        }

    fun objectFields(
        field: ManualField,
        value: ManualValue?,
        selectors: Map<String, String>,
    ): List<ManualField> {
        val resolved = field.resolved(selectors, value)
        resolved.reference?.let {
            return fields(it)
        }
        val variants = resolved.metadata.getAsJsonObject("variants") ?: return emptyList()
        val discriminator = resolved.metadata["discriminator"]?.asString ?: return emptyList()
        val selection = value?.field(discriminator)?.text ?: variants.keySet().first()
        val type =
            ManualField(
                discriminator,
                JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("label", "Тип")
                    addProperty("help", "Формат этих параметров.")
                    addProperty("required", true)
                    addProperty("default", variants.keySet().first())
                    add(
                        "enum",
                        com.google.gson.JsonArray().apply { variants.keySet().forEach { add(it) } },
                    )
                },
            )
        return listOf(type) +
            variants[selection]?.asString?.let(::fields).orEmpty().filterNot {
                it.key == discriminator
            }
    }

    fun selectors(
        fields: List<ManualField>,
        value: ManualValue,
        inherited: Map<String, String>,
    ): Map<String, String> {
        val result = inherited.toMutableMap()
        fields.forEach { field ->
            val child = value.field(field.key)
            val default = field.metadata["default"]
            when {
                child != null && child.type !in listOf("object", "array", "map") ->
                    result[field.key] = child.text
                default?.isJsonPrimitive == true -> result[field.key] = default.asString
                field.options.isNotEmpty() -> result[field.key] = field.options.first()
                else -> result.remove(field.key)
            }
        }
        return result
    }

    fun initial(field: ManualField, selectors: Map<String, String> = emptyMap()): ManualValue {
        val resolved = field.resolved(selectors, null)
        resolved.metadata["default"]
            ?.takeUnless { it.isJsonNull }
            ?.let {
                return fromJson(it)
            }
        return when (resolved.type) {
            "object" -> objectDefaults(objectFields(resolved, null, selectors), selectors)
            "array" -> ManualValue.arrayValue()
            "map" -> ManualValue.objectValue()
            "boolean" -> ManualValue("boolean", "false")
            "integer" -> ManualValue("integer", "")
            else -> ManualValue(resolved.type, resolved.options.firstOrNull().orEmpty())
        }
    }

    fun objectDefaults(
        fields: List<ManualField>,
        inherited: Map<String, String> = emptyMap(),
    ): ManualValue {
        var value = ManualValue.objectValue()
        fields.forEach { field ->
            val selected = selectors(fields, value, inherited)
            if (field.required && field.visible(selected))
                value = value.withField(field.key, initial(field, selected))
        }
        return value
    }

    fun initialDraft(
        protocolId: String = "vless",
        previous: ManualServerDraft? = null,
    ): ManualServerDraft {
        val protocol = protocol(protocolId)
        var settings = objectDefaults(fields(protocol.schema))
        for (key in listOf("address", "port")) {
            previous?.settings?.field(key)?.let {
                if (settings.field(key)?.type == it.type) settings = settings.withField(key, it)
            }
        }
        val stream =
            if (protocol.transport)
                ManualValue.objectValue(
                    mapOf(
                        "network" to ManualValue(text = protocol.defaultTransport),
                        "security" to ManualValue(text = protocol.defaultSecurity),
                    )
                )
            else null
        return ManualServerDraft(previous?.name.orEmpty(), protocolId, settings, stream)
    }

    companion object {
        fun fromAssets(context: Context): ManualSchemaRegistry =
            fromJson(
                context.assets.open("manual_protocols.json").bufferedReader().use { it.readText() },
                context.assets.open("manual_streams.json").bufferedReader().use { it.readText() },
            )

        fun fromJson(protocolJson: String, streamJson: String): ManualSchemaRegistry {
            val protocolRoot = JsonParser.parseString(protocolJson).asJsonObject
            val streamRoot = JsonParser.parseString(streamJson).asJsonObject
            require(
                protocolRoot["version"].asString == "26.9.30" &&
                    streamRoot["version"].asString == "26.9.30"
            )
            val protocols =
                protocolRoot.getAsJsonObject("protocols").entrySet().map { (id, raw) ->
                    raw.asJsonObject.let {
                        ManualProtocol(
                            id,
                            it["label"].asString,
                            it["schema"].asString,
                            it["transport"].asBoolean,
                            it["defaultTransport"].asString,
                            it["defaultSecurity"].asString,
                        )
                    }
                }
            val schemas =
                (protocolRoot.getAsJsonObject("schemas").entrySet() +
                        streamRoot.getAsJsonObject("schemas").entrySet())
                    .associate { it.key to it.value.asJsonObject }
            return ManualSchemaRegistry(protocols, schemas)
        }

        private fun fromJson(value: JsonElement): ManualValue =
            when {
                value.isJsonObject ->
                    ManualValue.objectValue(
                        value.asJsonObject.entrySet().associate { it.key to fromJson(it.value) }
                    )
                value.isJsonArray -> ManualValue.arrayValue(value.asJsonArray.map(::fromJson))
                value.asJsonPrimitive.isBoolean -> ManualValue("boolean", value.asString)
                value.asJsonPrimitive.isNumber -> ManualValue("integer", value.asString)
                else -> ManualValue("string", value.asString)
            }
    }
}
