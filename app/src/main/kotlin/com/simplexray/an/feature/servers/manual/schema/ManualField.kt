package com.simplexray.an.feature.servers.manual.schema

import com.google.gson.JsonObject
import com.simplexray.an.feature.servers.manual.model.ManualValue

data class ManualField(val key: String, val metadata: JsonObject) {
    val label: String
        get() = metadata["label"]?.asString ?: key

    val help: String
        get() = metadata["help"]?.asString ?: "Не задано — используется значение ядра."

    val type: String
        get() = metadata["type"]?.asString ?: "object"

    val required: Boolean
        get() = metadata["required"]?.asBoolean == true

    val secret: Boolean
        get() = metadata["secret"]?.asBoolean == true

    val options: List<String>
        get() = metadata.getAsJsonArray("enum")?.map { it.asString }.orEmpty()

    val reference: String?
        get() = metadata["ref"]?.asString

    val item: ManualField?
        get() = metadata.getAsJsonObject("items")?.let { ManualField("item", it) }

    val mapValue: ManualField?
        get() = metadata.getAsJsonObject("values")?.let { ManualField("value", it) }

    fun visible(selectors: Map<String, String>): Boolean {
        val condition = metadata.getAsJsonObject("when") ?: return true
        val value = selectors[condition["key"].asString].orEmpty()
        condition.getAsJsonArray("values")?.let {
            return it.any { item -> item.asString == value }
        }
        condition["prefix"]?.let {
            return value.startsWith(it.asString)
        }
        condition["notPrefix"]?.let {
            return !value.startsWith(it.asString)
        }
        return true
    }

    fun resolved(selectors: Map<String, String>, value: ManualValue?): ManualField {
        val variants = metadata["variants"] ?: return this
        if (type == "union" && variants.isJsonArray) {
            val candidates = variants.asJsonArray.map { ManualField(key, it.asJsonObject) }
            val chosen =
                if (metadata.has("selector")) candidates.firstOrNull { it.visible(selectors) }
                else candidates.firstOrNull { it.type == value?.type } ?: candidates.firstOrNull()
            return chosen?.let {
                ManualField(
                    key,
                    metadata.deepCopy().apply {
                        remove("variants")
                        remove("selector")
                        it.metadata.entrySet().forEach { (name, child) ->
                            add(name, child.deepCopy())
                        }
                        addProperty("label", label)
                        addProperty("help", help)
                    },
                )
            } ?: this
        }
        if (metadata.has("selector") && variants.isJsonObject) {
            val selection = selectors[metadata["selector"].asString]
            val reference = variants.asJsonObject[selection]?.asString ?: return this
            return ManualField(
                key,
                metadata.deepCopy().apply {
                    remove("variants")
                    remove("selector")
                    addProperty("ref", reference)
                },
            )
        }
        return this
    }
}

data class ManualProtocol(
    val id: String,
    val label: String,
    val schema: String,
    val transport: Boolean,
    val defaultTransport: String,
    val defaultSecurity: String,
)
